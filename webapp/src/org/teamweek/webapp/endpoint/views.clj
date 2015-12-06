(ns org.teamweek.webapp.endpoint.views
  (:require [clojure.string :as str]
            [ring.util.anti-forgery :refer (anti-forgery-field)]
            [datomic.api :as d]
            [hiccup.page :as page]
            [hiccup.form :as form]))

(str/split "* * * MON,FI" #" ")

(def day-opts [["MON" false "Monday"] ["TUE" false "Tuesday"] ["WED" false "Wednesday"]
               ["THU" false "Thursday"] ["FRI" false "Friday"] ["SAT" false "Saturday"]
               ["SUN" false "Sunday"]])

(defn parse-cron-string
  "Naive implementation. Only handles MON-SUN + hour.
  If I'm not able to parse this I return the literal cron
  string instead"
  [cron]
  (if (empty? cron)
    {:days day-opts
     :hr "13"}
    (let [[s m hr q s1 days s2] (str/split cron #" ")]
      (if (and (= "0" s)
               (= "0" m)
               (= "?" q)
               (= "*" s1)
               (= "*" s2))
        ;; Ok, this seems to be generated from the checkboxes
        (let [days (set (str/split days #","))]
          {:days (for [[val _ text] day-opts]
                   [val (contains? days val) text])
           :hr hr})
        ;; Nope, this seems to be custom cron
        cron))))


(defn team-page [req token]
  (let [db (:db req)
        data (d/pull db
                     [:team/name :team/members :team/schedule :team/domain :team/questions]
                     [:team/token token])
        schedule (if (empty? (:team/schedule data))
                   day-opts
                   (parse-cron-string (:team/schedule data)))]
    (page/html5
          [:head
           [:title "Welcome to Teamweek"]
           (page/include-css "https://cdnjs.cloudflare.com/ajax/libs/pure/0.6.0/pure-min.css")]
          [:body
           [:h1 "Welcome " (or (:team/name data)
                               (:team/domain data))]
           [:a {:href "/team/logout"} "Logout"]
           (form/form-to
            [:post "/team/update"]
            (anti-forgery-field)
            (if (map? schedule)
              (concat (for [[val checked? text] (:days schedule)]
                        (list [:br]
                              (form/check-box val checked? val)
                              (form/label val text)))
                      [[:br]
                       (form/label "hr" "Hour: ")
                       (form/drop-down "hr" (map str (range 24)) (:hr schedule))
                       [:br]
                       (form/label "schedule_cron" "Cron string (only for advanced users/testing):")
                       (form/text-field "schedule_cron" "")])
              (list (form/label "schedule_cron" "Cron string (use a cron string like '0 0 12 ? * FRI *' to get the checkboxes back):")
                    (form/text-field "schedule_cron" schedule)))
            [:br]
            (form/label "members" "Members:")
            (form/text-area "members"
                            (str/join "\n" (map :member/name (:team/members data))))
            [:br]
            (form/label "questions" "Questions:")
            (form/text-area {:readonly true}
                            "questions"
                            (str/join "\n" (map :question/text (:team/questions data))))
            [:br]
            (form/submit-button "Update team settings"))])))
