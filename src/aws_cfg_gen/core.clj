(ns aws-cfg-gen.core
  (:gen-class)
  (:require [uuid :refer [uuid]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.tools.cli :refer [parse-opts] :as cli]
            [clojure.string :as string]
            [clojure.set :as set]))

;; CLI helper functions
;;

;; Extend parse-opts to include actions and mandatory options
;; Get this reviewed especially the building up of the map
(defn parse-opts+
  "Take an action (may be nil), the set of options from parse-opts,
  and the opts+ spec to determine if the action is a valid one and all required
  options to an action are present. Generate a map for a result similar to the
  parse-opts map."
  [action options options+]
  (let [{:keys [required-options
                valid-actions]} options+
        result {:action+ nil
                :missing-options+ nil
                :errors+ nil
                :summary+ nil}]
    (-> result
        (as-> x (if (not-empty action)
                  (if (contains? valid-actions action)
                    (assoc x :action+ action)
                    (assoc x :errors+
                           (into []
                                 (conj (:errors+ x) (str "Invalid action: " action)))))
                  x))
        (assoc :missing-options+
               (into []
                     (set/difference required-options (keys options))))
        (as-> y (if (not-empty (y :missing-options+))
                  (assoc y :errors+
                         (into []
                               (conj (y :errors+)
                                     (str "Missing required options: "
                                          (string/join ", " (map (comp (partial str "--") name) (y :missing-options+)))))))
                  y))
        (assoc :summary+
               (->> [(if (not-empty valid-actions)
                       (->> ["Actions"
                             (string/join "\n" (map #(str "  " %) valid-actions))]
                            (string/join "\n"))
                       [])
                     (if (not-empty required-options)
                       (->> ["Required Options"
                             (string/join "\n" (map #(str "  --" %) (map name required-options)))
                             ""]
                            (string/join "\n"))
                       [])]
                    (flatten)
                    (string/join "\n"))))))

(defn error-msg [errors]
  (str "The following errors occurred parsing the cli:\n\n"
       (string/join \newline errors)))

(defn exit [status msg]
  (println msg)
  "System/exit"
  ;;(System/exit status)
)

(defn cli-summary
  "Display CLI summary info"
  [action summary summary+]
  (->> [(str "Usage: aws-cfg-gen [options] " (if (nil? action) "[action]" action))
        ""
        "Options:"
        summary
        ""
        summary+
        ""]
       (string/join \newline)))

(defn create-cli-handler
  "With two option args we are dealing with action routing
  with three options we are dealing with handling an actions options"
  ([cli-options cli-options+]
   (fn [& args]
     (let [{:keys [options
                   arguments
                   errors
                   summary]} (parse-opts args cli-options
                                         :in-order true
                                         :strict true)
           action (first arguments)
           {:keys [missing-options+
                   action+
                   errors+
                   summary+]} (parse-opts+ action options cli-options+)]
       (if (nil? (cond
                   (:help options) (exit 0 (cli-summary action summary summary+))
                   errors (exit 1 (error-msg errors))
                   errors+ (exit 1 (error-msg errors+))
                   (not action+) (exit 0 (cli-summary nil summary summary+))
                   (not (resolve (symbol action+))) (exit 2 (str "No function to dispatch the action (" action+ ") to."))))
         (apply (resolve (symbol action+)) options (rest arguments))
         (print (str "System/exit"))))))
   ([cli-options cli-options+ option-fn]
    (fn [top-level-options & args]
      (let [{:keys [options
                    arguments
                    errors
                    summary]} (parse-opts args cli-options
                                          :in-order true
                                          :strict true)
            action (first arguments)
            {:keys [missing-options+
                    action+
                    errors+
                    summary+]} (parse-opts+ action options cli-options+)]
        (if (nil? (cond
                    (:help options) (exit 0 (cli-summary action summary summary+))
                    errors (exit 1 (error-msg errors))
                    errors+ (exit 1 (error-msg errors+))
                    (not option-fn) (exit 2 "No option function to dispatch to.")))
          (option-fn (into options top-level-options))
          (print (str "System/exit")))))))

;; The CLI proper
;;
;; Initial CLI option config (extended by parse-opts+)
;;

;; Top-level config or main
(def main-cli-options
  [["-h" "--help"]])

(def main-cli-options+
  {:required-options #{}
   :valid-actions #{"generate" "add-account" "boosh"}})

;; generate action i.e, build the config file
(def generate-cli-options
  [["-h" "--help"]])

(def generate-cli-options+
  {:required-options #{}
   :valid-actions #{}})

;; add-acccount action
(def add-account-cli-options
  [["-n" "--name NAME" "Name of AWS account"]
   ["-i" "--id ID" "ID of AWS account"]
   ["-h" "--help"]])

(def add-account-cli-options+
  {:required-options #{:name :id}
   :valid-actions #{}})

(def -main (create-cli-handler main-cli-options main-cli-options+))
(def generate (create-cli-handler generate-cli-options generate-cli-options+ identity))
(def add-account (create-cli-handler add-account-cli-options add-account-cli-options+ identity))

;; Sub Account functions
;; sa - sub-accounts
;;

(def sa-config-path "resources")
(def sa-config-url (str sa-config-path "/sub-accounts.edn"))

(defn read-sa
  "Read sub-account data into a sorted map"
  [url]
  (-> url
      slurp
      edn/read-string
      (->> (into (sorted-map)))))

(defn write-sa
  "Writes sub-account info to sa-config-url"
  [sa]
  (-> sa
      (pprint/write :stream nil)
      (->> (spit sa-config-url))))

(defn new-sa
  "Create a new sa hash"
  [name id]
  {(keyword name) {
                   :external_id_ro (uuid)
                   :external_id_rw (uuid)
                   :id id}})
