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
      [:div.pure-g
       [:div.pure-u-1-3]
       [:div.pure-u-1-3
        [:div {:style "text-align: center;"}
         [:h1 "Welcome " [:span (or (:team/name data)
                                    (:team/domain data))] ]]
        [:div
         "Welcome to your team page. Here you can set the schedule for the questionnaire as well as the list of users you want to send the questions to.<br><em>Note: The Cron expression is only meant for testing and will be removed in a future release</em>"]
        [:div
         (form/form-to
          {:class "pure-form pure-form-stacked"}
          [:post "/team/update"]
          (anti-forgery-field)
          [:div.pure-control-group
           (if (map? schedule)
            (concat (for [[val checked? text] (:days schedule)]
                      [:label.pure-checkbox {:for val}
                       [:input {:type "checkbox" :value val :id val :name val :checked checked?} text]])
                    [(form/label "hr" "Hour: ")
                     (form/drop-down "hr" (map str (range 24)) (:hr schedule))

                     (form/label "schedule_cron" "Cron expression")
                     (form/text-field "schedule_cron" "")])
            (list (form/label "schedule_cron" "(use the following expression to get the checkboxes back) <pre>0 0 12 ? * FRI *</pre>")
                  (form/text-field "schedule_cron" schedule)))]
          (form/label "members" "Members:")
          (form/text-area {:style "width: 100%; height: 150px;"}
                          "members"
                          (str/join "\n" (map :member/name (:team/members data))))
          (form/label "questions" "Questions:")
          (form/text-area {:readonly true
                           :style "width: 100%;"}
                          "questions"
                          (str/join "\n" (map :question/text (:team/questions data))))
          (form/submit-button {:class "pure-button pure-button-primary"} "Update team settings"))
         [:a {:href "/team/logout"} "Logout"]]]]])))
