(ns org.teamweek.webapp.endpoint.team
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [compojure.core :refer :all]
            [ring.util.response :refer :all]
            [ring.middleware.flash :refer [flash-response]]
            [clj-http.client :as http]
            [cheshire.core :refer [parse-string]]
            [datomic.api :as d]
            [org.teamweek.webapp.endpoint.views :as views]))


(defn filter-user-data
  "Extract useful data from a user"
  [usr]
  {:name (:name usr)
   :email (get-in usr [:profile :email])
   :bot? (:is_bot usr)})

(defn get-team-data
  "Get team data based on a given token"
  [token]
  (let [req (http/get (str "https://slack.com/api/rtm.start?token=" token))
        team-data (parse-string (:body req) true)
        domain (get-in team-data [:team :domain])
        name (get-in team-data [:team :name])
        members (remove #(or (= (:name %) "slackbot")(:bot? %))
                  (map filter-user-data (:users team-data)))]
    {:domain domain
     :name name
     :members members}))

(defn create-team
  "Creates a team based on a token"
  [conn token team-data]
  (let [team-tx {:db/id (d/tempid :db.part/user)
                 :team/domain (:domain team-data)
                 :team/token token
                 :team/name (:name team-data)
                 :team/schedule "0 0 13 ? * FRI *" ; every FRI at 13:00
                 :team/members (for [{:keys [email name]} (:members team-data)]
                                 {:member/name name
                                  :member/email email})
                 :team/questions [{:question/text "What have you accomplished this week?"
                                   :question/order 1}
                                  {:question/text "What you commit to do next week?"
                                   :question/order 2}]}]
    @(d/transact conn [team-tx])))

(defn build-team-settings-update-tx [db token
                                     current-schedule new-schedule
                                     current-members new-members]
  (let [tx []
        tx (when (not= current-schedule new-schedule)
             (conj tx [:db/add [:team/token token] :team/schedule new-schedule]))
        removed-members (set/difference current-members new-members)
        added-members (set/difference new-members current-members)
        removed-tx (remove nil?
                           (for [member removed-members]
                             (let [member-eid (d/q '[:find ?member .
                                                     :in $ ?token ?name
                                                     :where
                                                     [?team :team/token ?token]
                                                     [?team :team/members ?member]
                                                     [?member :member/name ?name]]
                                                   db token member)]
                               (when member-eid
                                 [:db.fn/retractEntity member-eid]))))
        added-tx (for [member added-members]
                   {:db/id (d/tempid :db.part/user)
                    :member/name member
                    :team/_members [:team/token token]})]
    (reduce into tx [removed-tx added-tx])))

(defn get-schedule [{:keys [schedule_cron MON TUE WED THU FRI SAT SUN hr] :as params}]
  (if (empty? schedule_cron)
    (if (every? nil? [MON TUE WED THU FRI SAT SUN])
      ""
      (let [days (str/join "," (remove nil? [MON TUE WED THU FRI SAT SUN]))
          hr (or hr 12)]
        (format "0 0 %s ? * %s *" hr days)))
    (str/trim schedule_cron)))

;; Only schedule and members for now
(defn update-team-settings [req]
  (let [db (:db req)
        token (get-in req [:session "teamweek-token"])
        params (:params req)
        new-schedule (get-schedule (:params req))
        new-members (set (remove empty? (when members (map str/trim (str/split (:members params) #"\n")))))
        current-schedule (:team/schedule (d/entity db [:team/token token]))
        current-members (set (d/q '[:find [?name ...]
                                    :in $ ?token
                                    :where
                                    [?team :team/token ?token]
                                    [?team :team/members ?member]
                                    [?member :member/name ?name]]
                                  db token))
        tx (build-team-settings-update-tx db token
                                          current-schedule new-schedule
                                          current-members new-members)]
    (when-not (empty? tx)
      @(d/transact (:conn req) tx))))

(defn team-endpoint [config]
  (context "/team" []
    (GET "/" req
      (if-let [token (get (:session req) "teamweek-token")]
        (views/team-page req token)
        (redirect "/")))

   (POST "/" req
     (if-let [token (get (:form-params req) "token")]
       (let [db (:db req)
             conn (:conn req)
             team (ffirst (d/q '[:find ?e :in $ ?t :where [?e :team/token ?t]] db token))]
         (if team
           (-> (redirect "/team")
               (assoc-in [:session "teamweek-token"] token))
           (let [team-data (get-team-data token)]
             (if (:domain team-data)
               (do
                 (create-team conn token team-data)
                 (->
                   (redirect "/team")
                   (assoc-in [:session "teamweek-token"] token)))
               (do
                 ;; How does this work?
                 ;; (flash-response {:flash "Invalid token"} req)
                 (redirect "/"))))))
       (redirect "/")))

   (POST "/update" req
         (update-team-settings req)
         (redirect "/team"))

   (GET "/logout" req
     (-> (redirect "/")
         (assoc :session nil)))))
