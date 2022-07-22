(ns dev
  (:require
   [clojure.string :as string]
   [clojure.set :as set]
   [clojure.walk :as walk]
   [hiccup.page :as page]
   [jaq.http.xrf.nio :as nio]
   [jaq.http.xrf.rf :as rf]
   [jaq.http.xrf.header :as header]
   [jaq.http.xrf.json :as json]
   [jaq.http.xrf.params :as params]
   [jaq.http.xrf.websocket :as websocket]
   [jaq.repl :as r]))

(def default-repl-token "JAQ-REPL-TOKEN")

(defn root [{:keys [headers]
             {:keys [x-appengine-city
                     x-appengine-country
                     x-appengine-region
                     x-appengine-user-ip
                     x-cloud-trace-context]} :headers
             :as x}]
  (page/html5
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:link {:rel "icon" :href "data:image/svg+xml,%3csvg/%3e"}]
    [:title "Westcoast NFT"]
    [:body
     [:p "running"]]]))

(def repl-rf
  (comp
   nio/selector-rf
   (nio/thread-rf
    (comp
     (nio/select-rf
      (comp
       (nio/bind-rf
        (comp
         (nio/accept-rf
          (comp
           ;; open connection
           (rf/repeatedly-rf
            (comp
             nio/valid-rf
             nio/read-rf
             nio/write-rf
             ;; parse request line and headers
             (nio/receive-rf
              (comp
               (comp
                (map (fn [{:keys [byte] :as x}]
                       (assoc x :char (char (-> byte (bit-and 0xff))))))
                header/request-line
                header/headers)))
             ;; normalize
             (rf/one-rf :http/request (comp
                                       rf/identity-rf))
             (map (fn [{{:keys [headers status path method params]} :http/request
                        :as x}]
                    (assoc x
                           :method method
                           :path path
                           :params params
                           :headers headers
                           :status status)))
             ;; request handlers
             (rf/choose-rf
              (fn [{:keys [path]}]
                (str "/"
                     (some-> path (string/split #"/") (second))))
              {"/repl" (comp
                        (rf/choose-rf
                         :path
                         {"/repl" (comp
                                   ;; parse post body
                                   (nio/receive-rf
                                    (comp
                                     (map (fn [{:keys [byte] :as x}]
                                            (assoc x :char (char byte))))
                                     params/body))
                                   (rf/one-rf :params (comp
                                                       (map :params)))
                                   (rf/choose-rf
                                    (fn [{:keys [method]
                                          {:keys [content-type]} :headers
                                          {input :form session-id :device-id :keys [repl-token repl-type]} :params}]
                                      [content-type method repl-type repl-token])
                                    {["application/x-www-form-urlencoded" :POST ":clj" default-repl-token]
                                     (comp
                                      (map (fn [{{input :form session-id :device-id :keys [repl-token] :as params} :params
                                                 :keys [headers] :as x}]
                                             (->> {:input input :session-id session-id}
                                                  (jaq.repl/session-repl)
                                                  ((fn [{:keys [val ns ms]}]
                                                     (assoc x
                                                            :params params
                                                            :http/status 200
                                                            :http/reason "OK"
                                                            :http/headers {:content-type "text/plain"
                                                                           :connection "keep-alive"}
                                                            :http/body (str ns " => " val " - " ms "ms" "\n")))))))
                                      (comp
                                       nio/writable-rf
                                       (nio/send-rf (comp
                                                     nio/response-rf))
                                       nio/readable-rf))
                                     :default (comp
                                               (map (fn [{:keys [uuid] :as x}]
                                                      (assoc x
                                                             :http/status 403
                                                             :http/reason "FORBIDDEN"
                                                             :http/headers {:content-type "text/plain"
                                                                            :connection "keep-alive"}
                                                             :http/body "Forbidden")))
                                               (comp
                                                nio/writable-rf
                                                (nio/send-rf (comp
                                                              nio/response-rf))
                                                nio/readable-rf))}))}))
               :default (comp
                         (map (fn [{:http/keys [path]  :as x}]
                                (assoc x
                                       :http/status 200
                                       :http/reason "OK"
                                       :http/headers {:content-type "text/html"
                                                      :connection "keep-alive"}
                                       :http/body (root x))))
                         (comp
                          nio/writable-rf
                          (nio/send-rf (comp
                                        nio/response-rf))
                          nio/readable-rf))})))
           nio/valid-rf))))))
     nio/close-rf))))

(def env
  (->> (System/getenv)
       (into {})
       (walk/keywordize-keys)))

(defn register! []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (prn ex "Uncaught exception on" (.getName thread))))))

(defn -main [& args]
  (register!)
  (def s
    (->> [{:context/bip-size (* 20 4096)
           :context/repl-clients (volatile! {})
           :http/port (or (some-> env :PORT (Integer/parseInt)) 3000)
           :http/host "localhost"
           :http/scheme :http
           :http/minor 1 :http/major 1}]
         (into [] repl-rf)
         (first))))

#(
  *ns*
  )
