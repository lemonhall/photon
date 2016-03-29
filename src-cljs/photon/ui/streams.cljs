(ns photon.ui.streams
  (:use [jayq.core :only [$ css html]])
  (:require [om.next :as om :refer-macros [defui]]
            [cljs.core.async :refer [chan <! >! put! close!]]
            [om.dom :as dom]
            [goog.events :as events]
            [photon.ui.utils :as u]
            [photon.ui.components :as comp]
            [photon.ui.ws :as ws])
  (:import goog.net.IframeIo
           goog.net.EventType))

(def k->header {:stream "Stream name"
                :fn "Function"
                :export "Export"
                :last-error "Last error"
                :contents "Contents"
                :current-value "Current value"
                :avg-time "Avg. time/event"
                :status "Status"
                :url "Data link"
                :language "Language"
                :payload-size "Payload size"
                :processed "Events processed"
                :last-event "Last event processed"
                :stream-name "Target stream"
                :total-events "Stream size"
                :encoding "Encoding"
                :schema "Schema URL"
                :service-id "Sending Service"
                :local-id "Local UUID"
                :photon-timestamp "Photon Timestamp"
                :server-timestamp "Service Timestamp"
                :projection-name "Projection name"})

(defn clean-stream [stream]
  (-> stream
      (dissoc :schemas)
      (assoc :export :action/export-stream)
      (assoc :contents :action/view-contents)))

(defn strip-event [event]
  (assoc (dissoc event :payload :provenance :order-id)
         :action/show-payload :action/show-payload
         :meta/original event
         :payload-size (count (pr-str (:payload event)))))

(defui EventListItem
  Object
  (render
   [this]
   (let [params (om/props this)
         event (:event params)
         data (:data params)
         payload (:payload event)
         id (str (:service-id event) ":" (:local-id event))]
     (let [current? (= (:current data) id)]
       (apply dom/tr
              nil
              (map #(dom/td nil
                            (condp = (key %)
                              :url
                              (dom/a #js
                                     {:href (val %)} (val %))
                              (str (val %))))
                   event)
              #_(if current?
                  (dom/pre
                   nil
                   (dom/code
                    #js {:className "clojure"}
                    (clj->str event)))))))))

(defui ActiveStreams
  static om/IQuery
  (query [this] `[:stream-info])
  Object
  (componentDidUpdate
   [this prev-props prev-state]
   (let [active-modal (:modal-events (:ui-state (om/props this)))]
     (when (not (nil? active-modal))
       (.modal ($ :#modal-events) #js {:keyboard true :show active-modal})
       (when-not active-modal
         (.remove ($ ".modal-backdrop.fade.in"))))))
  (render
   [this]
   (let [data (:stream-info (om/props this))
         active-stream (:active-stream (:ui-state data))
         streams (map clean-stream (:streams data))
         events (map strip-event (:events (:ui-state data)))]
     (dom/div
      nil
      (dom/div
       #js {:className "page-title"}
       (dom/div
        #js {:className "title_left"}
        (dom/h3
         nil "Stream browser")))
      #_(dom/p nil (pr-str (:ui-state data)))
      (dom/div #js {:className "clearfix"})
      (dom/div
       #js {:className "row"}
       ((om/factory comp/LongPanel)
        {:title "Currently active streams"
         :component comp/Table
         :data {:data streams
                :flash [:stream (:new-stream (:ui-state data))]
                :owner this
                :rows [:stream :total-events :contents :export]}}))
      (if (not (nil? active-stream))
        (dom/div
         #js {:id "event-browser" :className "row"}
         ((om/factory comp/LongPanel)
          {:title "Event browser"
           :component comp/Table
           :data {:data events
                  :owner this
                  :rows [:service-id :local-id
                         :payload-size :stream-name
                         :action/show-payload]}})))
      ((om/factory comp/Modal)
       {:id "modal-events"
        :title "Event viewer"
        :onHide (fn [_]
                  (om/transact! this `[(ui/update {:k :modal-events :v false})
                                       :stream-info]))
        :component ((om/factory comp/CodeBlock)
                    {:code
                     (if-let [code (:meta/original
                                    (:selected-event (:ui-state data)))]
                       code "")
                     :modal? true})})
      #_(if (not (nil? active-stream))
          ((om/factory EventList)
           {:events (:events (:ui-state data)) :stream active-stream}))))))

(defn handle-iframe-response [parent json-msg]
  (let [msg (try (u/js->cljk (.getResponseJson json-msg))
                 (catch js/Error e e))]
    #_(.log js/console (str "iframe-response: " msg))
    (ws/notify-stream parent msg)
    (if (= "OK" (:status msg))
      (str "Uploaded to stream: " (:stream-name msg))
      (str "Unexpected error: " (pr-str msg)))))

(defn iframeio-upload-file [form-id owner parent]
  (let [el (.getDOMNode (om/react-ref owner form-id))
        iframe (IframeIo.)]
    #_(.log js/console el)
    (events/listen iframe EventType.COMPLETE
        (fn [event]
          (om/update-state!
           owner assoc :upload-status
           (handle-iframe-response parent iframe))
          (.dispose iframe)))
    (.sendFromForm iframe el)))

(defui NewStreamForm
  Object
  (render
   [this]
   (let [state (om/props this)
         owner (:owner state)]
     (apply dom/form
            #js {:ref "upload-form"
                 :className "form-horizontal form-label-left"
                 :method "POST"
                 :encType "multipart/form-data"
                 :onSubmit (fn [e]
                             (.preventDefault e)
                             (om/update-state!
                              this assoc :upload-status "Uploading...")
                             (iframeio-upload-file "upload-form" this
                                                   owner))
                 :action "/api/new-stream"}
            (:upload-status (om/get-state this))
            ((om/factory comp/LabelAndTextInput)
             {:owner owner :key :name :val (:name state)
              :label "Stream name (optional)"})
            ((om/factory comp/LabelAndSomething)
             {:owner owner
              :component (dom/select
                          #js {:className "form-control"
                               :value (:sform/select-value state)
                               :onChange
                               (fn [ev]
                                 (om/transact!
                                  owner
                                  `[(ui/update ~{:k :sform/select-value
                                                 :v (.-value (.-target ev))})]))}
                          (dom/option #js {:value "pev"} "PEV file")
                          (dom/option #js {:value "file"} "JSON sequence file"))
              :label "Source type"})
            (condp = (:select-value state)
              "file" [((om/factory comp/LabelAndFileInput)
                       {:name "upload-file-name"})
                      ((om/factory comp/FormButton)
                       {:text "Declare stream"})]
              "pev" [((om/factory comp/LabelAndFileInput)
                      {:name "upload-pev-name"})
                     ((om/factory comp/FormButton)
                      {:text "Declare stream"})])))))

(defui NewStream
  static om/IQuery
  (query [this] `[:stream-info])
  Object
  (render
   [this]
   (dom/div
    nil
    #_(dom/p nil (pr-str (:ui-state data)))
    (dom/div #js {:className "clearfix"})
    (dom/div
     #js {:className "row"}
     ((om/factory comp/LongPanel)
      {:component NewStreamForm
       :title "New stream wizard"
       :data (assoc (:ui-state (:stream-info (om/props this)))
                    :owner this)})))))