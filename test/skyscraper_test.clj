(ns skyscraper-test
  (:require [clojure.test :refer :all]
            [clj-http.client :as http]
            [clojure.string :as string]
            [skyscraper :refer :all]
            [skyscraper.cache :as cache]
            [taoensso.timbre :as timbre]
            [net.cgrand.enlive-html :refer [select text]]))

(defn dummy-site-content
  [i]
  (format "<html><h1>Number %s</h1>%s</html>"
          i
          (if (>= i 100)
            ""
            (apply str (for [n (range 10) :let [m (+ n (* 10 i))] :when (pos? m)] (format "<a href='/%s'>Page %s</a>" m m))))))

(defn url-number
  [url]
  (Integer/parseInt (last (string/split url #"/"))))

(def hits (atom 0))

(defn mock-get
  [url & args]
  (swap! hits inc)
  {:body (dummy-site-content (url-number url))})

(defn process-root [res ctx]
  (let [num (text (first (select res [:h1])))
        subpages (select res [:a])]
    (if (seq subpages)
      (for [a subpages]
        {:processor :root, :url (href a)})
      {:number num})))

(defprocessor root
  :cache-key-fn (fn [ctx] (str "numbers/" (url-number (:url ctx))))
  :process-fn process-root)

(defprocessor root-uncached
  :process-fn process-root)

(defn seed [& _]
  [{:url "http://localhost/0", :processor :root}])

(defn seed-uncached [& _]
  [{:url "http://localhost/0", :processor :root-uncached}])

(timbre/set-level! :warn)

(deftest basic-scraping
  (with-redefs [http/get mock-get]
    (is (= (count (scrape :skyscraper-test/seed :html-cache nil :processed-cache nil))
           900))))

(deftest caches
  (reset! hits 0)
  (with-redefs [http/get mock-get]
    (let [cache (cache/memory)]
      (is (= (count (scrape :skyscraper-test/seed :html-cache cache :processed-cache cache)) 900))
      (let [hits-before @hits
            _ (dorun (scrape :skyscraper-test/seed :html-cache cache :processed-cache cache))
            hits-after @hits]
        (is (= hits-before hits-after)))
      (let [res1 (doall (scrape :skyscraper-test/seed :html-cache cache :processed-cache cache))
            res2 (doall (scrape :skyscraper-test/seed :html-cache cache :processed-cache nil))
            res3 (doall (scrape :skyscraper-test/seed :html-cache nil :processed-cache cache))
            res4 (doall (scrape :skyscraper-test/seed :html-cache nil :processed-cache nil))
            res5 (doall (scrape :skyscraper-test/seed-uncached :html-cache nil :processed-cache nil))]
        (is (= res1 res2 res3 res4 res5))))))

(deftest test-merge-urls
  (are [y z] (= (merge-urls "https://foo.pl/bar/baz" y) z)
       "http://bar.uk/baz/foo" "http://bar.uk/baz/foo"
       "//bar.uk/baz/foo" "https://bar.uk/baz/foo"
       "/baz/foo" "https://foo.pl/baz/foo"
       "foo" "https://foo.pl/bar/foo"))

(deftest test-allows
  (is (allows? {:k1 1, :k2 2} {:k1 1, :k3 3}))
  (is (not (allows? {:k1 1, :k2 2} {:k1 1, :k2 3})))
  (is (allows? {:k1 1} {:k1 1, :k2 2}))
  (is (allows? {} {:k1 1, :k2 2}))
  (is (allows? {:k1 1} {:k2 2})))

(defprocessor nil-url-test-processor-root
  :cache-template "nil-url"
  :process-fn (fn [res ctx]
                (for [a (select res [:a])]
                  {:title (text a), :url (href a), :processor :skyscraper-test/nil-url-test-processor-child})))

(defprocessor nil-url-test-processor-child
  :cache-template "nil-url/:title"
  :process-fn (fn [res ctx]
                [{:info (text (first (select res [:h1])))}]))

(deftest test-nil-url
  (let [html-main "<html><a href='/sub'>link</a><a name='anchor'>non-link</a></html>"
        html-child "<html><h1>Info</h1></html>"
        get (fn [url & args] {:body (if (string/ends-with? url "/sub") html-child html-main)})]
    (with-redefs [http/get get]
      (is (= (scrape [{:url "http://localhost/", :processor :skyscraper-test/nil-url-test-processor-root}] :html-cache nil :processed-cache nil)
             [{:title "link", :info "Info"}])))))
