;;;; Skyscraper - Core library

(ns skyscraper
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clj-http.client :as http]
            [skyscraper.cache :as cache]
            [net.cgrand.enlive-html :refer [html-resource select]]
            [taoensso.timbre :refer [infof warnf]]
            [slingshot.slingshot :refer [try+ throw+]]
            [clojure.set :refer [intersection]]
            [reaver])
  (:import [java.net URL SocketTimeoutException]))

;;; Directories

(def output-dir
  "All Skyscraper output, either temporary or final, goes under here."
  (str (System/getProperty "user.home") "/skyscraper-data/"))

(def html-cache-dir
  "Local copies of downloaded HTML files go here."
  (str output-dir "cache/html/"))

(def processed-cache-dir
  "Cache storing the interim results of processing HTML files."
  (str output-dir "cache/processed/"))

;;; Utility functions

(defn save
  "Saves a Clojure datum in a file named by name, creating its parent
   directories if necessary. The datum can be read back by read or
   clojure.edn/read."
  [name datum]
  (io/make-parents name)
  (with-open [f (io/writer name)]
    (binding [*out* f *print-length* nil *print-level* nil]
      (prn datum))))

;;; URL manipulation

(defn merge-urls
  "Fills the missing parts of new-url (which can be either absolute,
   root-relative, or relative) with corresponding parts from url
   (an absolute URL) to produce a new absolute URL."
  [url new-url]
  (str (URL. (URL. url) new-url)))

;;; Micro-templating framework

(defn format-template
  "Fills in a template string with moving parts from m. template should be
   a string containing 'variable names' starting with colons; these names
   are extracted, converted to keywords and looked up in m, which should be
   a map (or a function taking keywords and returning strings).

   Example:
   (format-template \":group/:user/index\" {:user \"joe\", :group \"admins\"})
   ;=> \"admins/joe/index\" "
  [template m]
  (let [re #":[a-z-]+"
        keys (map #(keyword (subs % 1)) (re-seq re template))
        fmt (string/replace template re "%s")]
    (apply format fmt (map m keys))))

;;; Downloading

(defn string-resource
  "Returns an Enlive resource for a HTML snippet passed as a string."
  [s]
  (html-resource (java.io.StringReader. s)))

(defn download
  "If a file named by local-path exists in the HTML cache, returns its
  content as a string.  Otherwise, downloads a file from the given URL,
  stores it in the cache and returns the cached file's textual content.
  Passes options to clj-http."
  [url local-path html-cache force options retries]
  (or
   (and local-path (not force) (cache/load-string html-cache local-path))
   (do
     (infof "Downloading %s -> %s" url local-path)
     (first
       (drop-while #(instance? Exception %)
         (repeatedly retries
           #(try+
              (let [html (:body (http/get url (into {:as :auto, :socket-timeout 5000, :decode-body-headers true} options)))]
                (when local-path
                  (cache/save-string html-cache local-path html))
                html)
              (catch map? e e)
              (catch SocketTimeoutException e
                (warnf "Timeout while trying to download %s, retrying" url)
                e))))))
   (throw (Exception. (str "Maximum number of retries exceeded: " url)))))

;;; Processors

(defn ensure-seq
  "Returns the argument verbatim if it's not a map. Otherwise, wraps it in a vector."
  [x]
  (if (map? x) [x] x))

(defn sanitize-cache
  "Converts a cache argument to the processor to a CacheBackend if it
   isn't one already."
  [value cache-dir]
  (cond
   (= value true) (cache/fs cache-dir)
   (not value) (cache/null)
   :otherwise value))

(defn ensure-processors
  "Ensures that every context in l that has a processor also
   has a next URL to visit."
  [l]
  (remove #(when (and (:processor %) (not (:url %)))
             (warnf "Context has a processor but no URL, skipping: %s" (pr-str %))
             true)
          l))

(defn enlive-parse
  "Parses the HTML to an Enlive resource."
  [html ctx]
  (string-resource html))

(defn reaver-parse
  "Parses the HTML to a JSoup document."
  [html ctx]
  (reaver/parse html))

(def ^:dynamic parse enlive-parse)

(defn default-error-handler
  "Warns about 404s yielding empty seqs, barfs on other errors."
  [url err]
  (if (= (:status err) 404)
    (do
      (warnf "Download returned error 404 Not Found, pruning scrape tree: %s" url)
      [])
    (throw+ err)))

(defn default-process-fn
  [res ctx]
  {:resource res})

(def ^:dynamic
  default-options
  {:error-handler default-error-handler,
   :html-cache true,
   :parse-fn #'parse,
   :process-fn default-process-fn,
   :processed-cache true,
   :retries 5,
   :update false,
   :url-fn :url})

(defn processor
  "Performs a single stage of scraping."
  [processor-name input-context options page-options]
  (let [options (merge default-options options page-options)
        {:keys [cache-template
                error-handler
                http-options
                parse-fn
                process-fn
                retries
                updatable
                update
                url-fn]} options
        html-cache (sanitize-cache (:html-cache options) html-cache-dir)
        processed-cache (sanitize-cache (:processed-cache options) processed-cache-dir)
        cache-key-fn (or (:cache-key-fn options)
                         (when cache-template
                           #(format-template cache-template %)))
        cache-key (when cache-key-fn
                    (cache-key-fn input-context))
        force (and update updatable)]
    (or (when (and cache-key (not force))
          (cache/load processed-cache cache-key))
        (let [url (url-fn input-context)
              input-context (assoc input-context :url url :cache-key cache-key)
              src (download url cache-key html-cache force http-options retries)
              processed (if (map? src)
                          (error-handler url src)
                          (->> (process-fn (parse-fn src input-context) input-context)
                               ensure-seq
                               ensure-processors
                               (map #(if (:url %) (update-in % [:url] (partial merge-urls url)) %))
                               vec))]
          (if cache-key
            (cache/save processed-cache cache-key processed)
            (infof "%s: not caching since :cache-template not specified" (name processor-name)))
          processed))))

(defmacro defprocessor
  [processor-name & opts]
  `(defn ~processor-name [context# user-opts#]
     (processor ~(keyword (name processor-name)) context# user-opts# ~(apply hash-map opts))))

;;; Scraping engine

;; From http://stackoverflow.com/questions/3407876
(defn unchunk [s]
  (when (seq s)
    (lazy-seq
      (cons (first s)
            (unchunk (next s))))))

;; From http://stackoverflow.com/questions/21943577/mapcat-breaking-the-lazyness
(defn my-mapcat
  "Like mapcat, but fully lazy."
  [f coll]
  (lazy-seq
   (when (not-empty coll)
     (concat
      (f (first coll))
      (my-mapcat f (rest coll))))))

(defn allows?
  "True if all keys in m1 that are also in m2 have equal values in both maps."
  [m1 m2]
  (let [ks (intersection (set (keys m1)) (set (keys m2)))]
    (if (seq ks)
      (let [f (apply juxt ks)]
        (= (f m1) (f m2)))
      true)))

(defn filter-contexts
  [data params]
  (if-let [only (:only params)]
    (let [filter-fn (if (fn? only)
                      only
                      (fn [x] (some #(allows? % x) (ensure-seq only))))]
      (filter filter-fn data))
    data))

(defn postprocess-contexts
  [data params]
  (if-let [postprocess (:postprocess params)]
    (postprocess data)
    data))

(defn resolve-processor
  [processor-key ns]
  (or
   (ns-resolve (symbol (or (namespace processor-key) ns (str *ns*))) (symbol (name processor-key)))
   (throw (Exception. (format "Unable to resolve processor: %s" processor-key)))))

(defn do-scrape
  ([data params]
   (let [ns (if (keyword? data) (namespace data))
         data-fn (if (keyword? data) (ns-resolve (symbol (or ns (str *ns*))) (symbol (name data))))
         data (if data-fn (data-fn) data)]
     (do-scrape (-> data
                    (filter-contexts params)
                    (postprocess-contexts params))
                params ns)))
  ([data params ns]
   (binding [*print-length* nil *print-level* nil]
     (my-mapcat (fn [x]
                  (if-let [processor-key (:processor x)]
                    (let [proc (resolve-processor processor-key ns)
                          input-context (dissoc x :processor)
                          res (unchunk (proc input-context params))
                          res (map (partial merge input-context) res)
                          res (filter-contexts res params)
                          res (postprocess-contexts res params)]
                      (do-scrape res params ns))
                    (list (dissoc x :url))))
                data))))
(defn scrape
  [data & {:as params}]
  (do-scrape data params))

(defn save-dataset-to-csv
  [data output & [keyseq]]
  (let [keyseq (or keyseq (keys (first data)))]
    (with-open [f (io/writer output)]
      (csv/write-csv f [(map name keyseq)])
      (csv/write-csv f (map (fn [row-data]
                              (map (comp str row-data) keyseq))
                            data)))))

(defn scrape-csv
  [seed output & {:keys [all-keys], :or {all-keys true}, :as params}]
  (if all-keys
    (let [ks (vec (reduce into #{} (map keys (do-scrape seed params))))
          data (do-scrape seed (assoc params :update false))]
      (save-dataset-to-csv data output ks))
    (save-dataset-to-csv (do-scrape seed params) output)))

(defn separate
  [pred coll]
  [(take-while pred coll) (drop-while pred coll)])

(defn uncompress-tree
  ([tree] (uncompress-tree [] tree))
  ([path tree]
   (let [[node subtrees] (separate (comp not vector?) tree)
         path (into path node)]
     (if (seq subtrees)
       (mapcat (partial uncompress-tree path) subtrees)
       [path]))))

;;; Enlive helpers

(defn href
  [x]
  (cond
   (nil? x) nil
   (and (map? x) (= :a (:tag x))) (-> x :attrs :href)
   :otherwise (href (first (select x [:a])))))
