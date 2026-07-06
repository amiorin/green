(ns green.ansible-test
  "Playbook selection, recap parsing, and inventory rendering need no
  ansible binary — only `ansible-step` itself shells out."
  (:require [clojure.test :refer [deftest is testing]]
            [green.ansible :as ansible])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmpdir []
  (str (Files/createTempDirectory "green-ansible" (make-array FileAttribute 0))))

(deftest playbook-follows-the-event
  (is (= "create.yml" (ansible/playbook {:green/event :create})))
  (is (= "create.yml" (ansible/playbook {:green/event :rotate}))
      "any non-delete event provisions")
  (is (= "delete.yml" (ansible/playbook {:green/event :delete})))
  (testing "partial overrides keep the other default"
    (is (= "teardown.yml"
           (ansible/playbook {:green/event :delete} {:delete "teardown.yml"})))
    (is (= "create.yml"
           (ansible/playbook {:green/event :create} {:delete "teardown.yml"})))))

(deftest recap-parses-per-host-counters
  (let [out (str "PLAY RECAP *********************************************\n"
                 "zk1                        : ok=7    changed=4    unreachable=0    failed=0    skipped=1    rescued=0    ignored=0\n"
                 "zk2                        : ok=7    changed=0    unreachable=0    failed=1    skipped=1    rescued=0    ignored=0\n")]
    (is (= {"zk1" {:ok 7 :changed 4 :unreachable 0 :failed 0
                   :skipped 1 :rescued 0 :ignored 0}
            "zk2" {:ok 7 :changed 0 :unreachable 0 :failed 1
                   :skipped 1 :rescued 0 :ignored 0}}
           (ansible/parse-recap out))))
  (is (= {} (ansible/parse-recap "no recap here"))))

(deftest inventory-ini-renders-groups-hosts-and-vars
  (is (= (str "[zookeeper]\n"
              "zk1 ansible_host=10.0.0.1 zk_id=1\n"
              "zk2 ansible_host=10.0.0.2 zk_id=2\n"
              "\n"
              "[zookeeper:vars]\n"
              "ansible_python_interpreter=/usr/bin/python3\n"
              "ansible_user=root\n")
         (ansible/inventory-ini
          {"zookeeper"
           {:hosts [{:name "zk1" :vars {:ansible_host "10.0.0.1" :zk_id 1}}
                    {:name "zk2" :vars {:ansible_host "10.0.0.2" :zk_id 2}}]
            :vars {:ansible_user "root"
                   :ansible_python_interpreter "/usr/bin/python3"}}}))))

(deftest inventory-ini-without-group-vars
  (is (= "[web]\nw1\n"
         (ansible/inventory-ini {:web {:hosts [{:name "w1"}]}}))))

(deftest inventory-advice-writes-the-file
  (let [dir (tmpdir)
        file (str dir "/hosts/inventory.ini")
        advice (ansible/inventory-advice
                (constantly file)
                (fn [opts]
                  {"zookeeper"
                   {:hosts (mapv (fn [{:keys [name ip]}]
                                   {:name name :vars {:ansible_host ip}})
                                 (:servers opts))}}))
        opts {:servers [{:name "zk1" :ip "10.0.0.1"}]}]
    (is (= opts (advice opts)) "before-advice passes opts through")
    (is (= "[zookeeper]\nzk1 ansible_host=10.0.0.1\n"
           (slurp file)))))
