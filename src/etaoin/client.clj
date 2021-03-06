(ns etaoin.client
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-http.client :as client]
            [cheshire.core :refer [parse-string]]
            [slingshot.slingshot :refer [throw+]]))

;;
;; defaults
;;

(def timeout
  "HTTP timeout in seconds. The current value may seems to high,
  but according to my experience with SPA application full of React
  modules even 20 seconds could not be enough for a driver to process
  your request."
  60)

(def default-api-params
  {:as :json
   :accept :json
   :content-type :json
   :socket-timeout (* 1000 timeout)
   :conn-timeout (* 1000 timeout)
   :form-params {}
   :debug false})

;;
;; helpers
;;

(defn- url-item-str [item]
  (cond
    (keyword? item) (name item)
    (symbol? item) (name item)
    (string? item) item
    :else (str item)))

(defn- get-url-path [items]
  (str/join "/" (map url-item-str items)))

(defn- status-selector [resp]
  (-> resp :status integer?))

(defmacro with-pool [opt & body]
  `(client/with-connection-pool ~opt
     ~@body))

(defn- parse-json [body]
  (let [body* (str/replace body #"Invalid Command Method -" "")]
    (try
      (parse-string body* true)
      (catch Throwable _ body))))

(defn- error-response [body]
  (if (string? body)
    (parse-json body)
    body))

;;
;; client
;;

(defn call
  [driver method path-args payload]
  (let [host (:host @driver)
        port (:port @driver)
        root-path (:root-path @driver)
        root-path (if (some #(% root-path) [string? keyword? symbol?]) 
                    [root-path]
                    (vec root-path))
        path-args (into root-path path-args)
        path (get-url-path path-args)
        url (format "http://%s:%s/%s" host port path)
        params (merge default-api-params
                      {:url url
                       :method method
                       :form-params (-> payload (or {}))
                       :throw-exceptions false})

        _ (log/debugf "%s %s:%s %6s %s %s"
                      (-> @driver :type name)
                      (-> @driver :host)
                      (-> @driver :port)
                      (-> method name str/upper-case)
                      path
                      (-> payload (or "")))

        resp (client/request params)
        body (:body resp)
        error (delay {:type :etaoin/http-error
                      :status (:status resp)
                      :driver @driver
                      :response (error-response body)
                      :host host
                      :port port
                      :method method
                      :path path
                      :payload payload})]
    (cond
      (-> resp :status (not= 200))
      (throw+ @error)

      (-> body :status (or 0) (> 0))
      (throw+ @error)

      :else
      body)))
