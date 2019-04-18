(ns metabase.driver.datomic.breakout-test
  (:require [clojure.test :refer :all]
            [metabase.driver.datomic.test :refer :all]
            [metabase.test.data :as data]))

(deftest breakout-single-column-test
  (is (= '{:find     [?checkins|checkins|user_id (count ?checkins)]
           :where    [[?checkins :checkins/user_id ?checkins|checkins|user_id]]
           :order-by [[:asc (:checkins/user_id ?checkins)]]
           :select   [(:checkins/user_id ?checkins) (count ?checkins)]}

         (with-datomic
           (query->native
            (data/mbql-query checkins
              {:aggregation [[:count]]
               :breakout    [$user_id]
               :order-by    [[:asc $user_id]]})))))

  (is (match? {:data {:rows    [[1 31] [2 70] [3 75] [4 77] [5 69]
                                [6 70] [7 76] [8 81] [9 68] [10 78]
                                [11 74] [12 59] [13 76] [14 62] [15 34]]
                      :columns ["user_id"
                                "count"]}}
              (with-datomic
                (data/run-mbql-query checkins
                  {:aggregation [[:count]]
                   :breakout    [$user_id]
                   :order-by    [[:asc $user_id]]})))))

(deftest breakout-aggregation-test
  (testing "This should act as a \"distinct values\" query and return ordered results"
    (is (match? {:data
                 {:columns ["user_id"],
                  :rows [[1] [2] [3] [4] [5] [6] [7] [8] [9] [10]]
                  :cols [{:name "user_id"}]}}

                (with-datomic
                  (data/run-mbql-query checkins
                    {:breakout [$user_id]
                     :limit    10}))))))

(deftest breakout-multiple-columns-implicit-order
  (testing "Fields should be implicitly ordered :ASC for all the fields in `breakout` that are not specified in `order-by`"
    (is (match? {:data
                 {:columns ["user_id" "venue_id" "count"]
                  :rows
                  [[1 1 1] [1 5 1] [1 7 1] [1 10 1]
                   [1 13 1] [1 16 1] [1 26 1] [1 31 1]
                   [1 35 1] [1 36 1]]}}
                (with-datomic
                  (data/run-mbql-query checkins
                    {:aggregation [[:count]]
                     :breakout    [$user_id $venue_id]
                     :limit       10}))))))
(deftest breakout-multiple-columns-explicit-order
  (testing "`breakout` should not implicitly order by any fields specified in `order-by`"
    (is (match?
         {:data
          {:rows    [[15 2 1] [15 3 1] [15 7 1] [15 14 1] [15 16 1] [15 18 1] [15 22 1] [15 23 2] [15 24 1] [15 27 1]]
           :columns ["user_id" "venue_id" "count"]}}
         (with-datomic
           (data/run-mbql-query checkins
             {:aggregation [[:count]]
              :breakout    [$user_id $venue_id]
              :order-by    [[:desc $user_id]]
              :limit       10}))))))

(comment


  (qp-expect-with-all-drivers
   {:rows        [[2 8 "Artisan"]
                  [3 2 "Asian"]
                  [4 2 "BBQ"]
                  [5 7 "Bakery"]
                  [6 2 "Bar"]]
    :columns     [(data/format-name "category_id")
                  "count"
                  "Foo"]
    :cols        [(assoc (breakout-col (venues-col :category_id))
                    :remapped_to "Foo")
                  (aggregate-col :count)
                  (#'add-dim-projections/create-remapped-col "Foo" (data/format-name "category_id"))]
    :native_form true}
   (data/with-data
     (fn []
       (let [venue-names (defs/field-values defs/test-data-map "categories" "name")]
         [(db/insert! Dimension {:field_id (data/id :venues :category_id)
                                 :name     "Foo"
                                 :type     :internal})
          (db/insert! FieldValues {:field_id              (data/id :venues :category_id)
                                   :values                (json/generate-string (range 0 (count venue-names)))
                                   :human_readable_values (json/generate-string venue-names)})]))
     (->> (data/run-mbql-query venues
            {:aggregation [[:count]]
             :breakout    [$category_id]
             :limit       5})
          booleanize-native-form
          (format-rows-by [int int str])
          tu/round-fingerprint-cols)))

  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :foreign-keys)
                                [["Wine Bar" "Thai" "Thai" "Thai" "Thai" "Steakhouse" "Steakhouse" "Steakhouse" "Steakhouse" "Southern"]
                                 ["American" "American" "American" "American" "American" "American" "American" "American" "Artisan" "Artisan"]]
                                (data/with-data
                                  (fn []
                                    [(db/insert! Dimension {:field_id                (data/id :venues :category_id)
                                                            :name                    "Foo"
                                                            :type                    :external
                                                            :human_readable_field_id (data/id :categories :name)})])
                                  [(->> (data/run-mbql-query venues
                                          {:order-by [[:desc $category_id]]
                                           :limit    10})
                                        rows
                                        (map last))
                                   (->> (data/run-mbql-query venues
                                          {:order-by [[:asc $category_id]]
                                           :limit    10})
                                        rows
                                        (map last))]))

  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                [[10.0 1] [32.0 4] [34.0 57] [36.0 29] [40.0 9]]
                                (format-rows-by [(partial u/round-to-decimals 1) int]
                                                (rows (data/run-mbql-query venues
                                                        {:aggregation [[:count]]
                                                         :breakout    [[:binning-strategy $latitude :num-bins 20]]}))))

  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                [[0.0 1] [20.0 90] [40.0 9]]
                                (format-rows-by [(partial u/round-to-decimals 1) int]
                                                (rows (data/run-mbql-query venues
                                                        {:aggregation [[:count]]
                                                         :breakout    [[:binning-strategy $latitude :num-bins 3]]}))))

  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                [[10.0 -170.0 1] [32.0 -120.0 4] [34.0 -120.0 57] [36.0 -125.0 29] [40.0 -75.0 9]]
                                (format-rows-by [(partial u/round-to-decimals 1) (partial u/round-to-decimals 1) int]
                                                (rows (data/run-mbql-query venues
                                                        {:aggregation [[:count]]
                                                         :breakout    [[:binning-strategy $latitude :num-bins 20]
                                                                       [:binning-strategy $longitude :num-bins 20]]}))))

  ;; Currently defaults to 8 bins when the number of bins isn't
  ;; specified
  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                [[10.0 1] [30.0 90] [40.0 9]]
                                (format-rows-by [(partial u/round-to-decimals 1) int]
                                                (rows (data/run-mbql-query venues
                                                        {:aggregation [[:count]]
                                                         :breakout    [[:binning-strategy $latitude :default]]}))))

  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                [[10.0 1] [30.0 61] [35.0 29] [40.0 9]]
                                (tu/with-temporary-setting-values [breakout-bin-width 5.0]
                                  (format-rows-by [(partial u/round-to-decimals 1) int]
                                                  (rows (data/run-mbql-query venues
                                                          {:aggregation [[:count]]
                                                           :breakout    [[:binning-strategy $latitude :default]]})))))

  ;; Testing bin-width
  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                [[10.0 1] [33.0 4] [34.0 57] [37.0 29] [40.0 9]]
                                (format-rows-by [(partial u/round-to-decimals 1) int]
                                                (rows (data/run-mbql-query venues
                                                        {:aggregation [[:count]]
                                                         :breakout    [[:binning-strategy $latitude :bin-width 1]]}))))

  ;; Testing bin-width using a float
  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                [[10.0 1] [32.5 61] [37.5 29] [40.0 9]]
                                (format-rows-by [(partial u/round-to-decimals 1) int]
                                                (rows (data/run-mbql-query venues
                                                        {:aggregation [[:count]]
                                                         :breakout    [[:binning-strategy $latitude :bin-width 2.5]]}))))

  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                [[33.0 4] [34.0 57]]
                                (tu/with-temporary-setting-values [breakout-bin-width 1.0]
                                  (format-rows-by [(partial u/round-to-decimals 1) int]
                                                  (rows (data/run-mbql-query venues
                                                          {:aggregation [[:count]]
                                                           :filter      [:and
                                                                         [:< $latitude 35]
                                                                         [:> $latitude 20]]
                                                           :breakout    [[:binning-strategy $latitude :default]]})))))

  (defn- round-binning-decimals [result]
    (let [round-to-decimal #(u/round-to-decimals 4 %)]
      (-> result
          (update :min_value round-to-decimal)
          (update :max_value round-to-decimal)
          (update-in [:binning_info :min_value] round-to-decimal)
          (update-in [:binning_info :max_value] round-to-decimal))))

  ;;Validate binning info is returned with the binning-strategy
  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                (assoc (breakout-col (venues-col :latitude))
                                  :binning_info {:min_value 10.0, :max_value 50.0, :num_bins 4, :bin_width 10.0, :binning_strategy :bin-width})
                                (-> (data/run-mbql-query venues
                                      {:aggregation [[:count]]
                                       :breakout    [[:binning-strategy $latitude :default]]})
                                    tu/round-fingerprint-cols
                                    (get-in [:data :cols])
                                    first))

  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                (assoc (breakout-col (venues-col :latitude))
                                  :binning_info {:min_value 7.5, :max_value 45.0, :num_bins 5, :bin_width 7.5, :binning_strategy :num-bins})
                                (-> (data/run-mbql-query venues
                                      {:aggregation [[:count]]
                                       :breakout    [[:binning-strategy $latitude :num-bins 5]]})
                                    tu/round-fingerprint-cols
                                    (get-in [:data :cols])
                                    first))

  ;;Validate binning info is returned with the binning-strategy
  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning)
                                {:status :failed
                                 :class  Exception
                                 :error  "Unable to bin Field without a min/max value"}
                                (tu/with-temp-vals-in-db Field (data/id :venues :latitude) {:fingerprint {:type {:type/Number {:min nil, :max nil}}}}
                                  (-> (tu.log/suppress-output
                                       (data/run-mbql-query venues
                                         {:aggregation [[:count]]
                                          :breakout    [[:binning-strategy $latitude :default]]}))
                                      (select-keys [:status :class :error]))))

  (defn- field->result-metadata [field]
    (select-keys field [:name :display_name :description :base_type :special_type :unit :fingerprint]))

  (defn- nested-venues-query [card-or-card-id]
    {:database metabase.models.database/virtual-id
     :type     :query
     :query    {:source-table (str "card__" (u/get-id card-or-card-id))
                :aggregation  [:count]
                :breakout     [[:binning-strategy [:field-literal (data/format-name :latitude) :type/Float] :num-bins 20]]}})

  ;; Binning should be allowed on nested queries that have result metadata
  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning :nested-queries)
                                [[10.0 1] [32.0 4] [34.0 57] [36.0 29] [40.0 9]]
                                (tt/with-temp Card [card {:dataset_query   {:database (data/id)
                                                                            :type     :query
                                                                            :query    {:source-query {:source-table (data/id :venues)}}}
                                                          :result_metadata (mapv field->result-metadata (db/select Field :table_id (data/id :venues)))}]
                                  (->> (nested-venues-query card)
                                       qp/process-query
                                       rows
                                       (format-rows-by [(partial u/round-to-decimals 1) int]))))

  ;; Binning is not supported when there is no fingerprint to determine boundaries
  (datasets/expect-with-drivers (non-timeseries-drivers-with-feature :binning :nested-queries)
                                Exception
                                (tu.log/suppress-output
                                 (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                                                           :type     :query
                                                                           :query    {:source-query {:source-table (data/id :venues)}}}}]
                                   (-> (nested-venues-query card)
                                       qp/process-query
                                       rows))))

  ;; if we include a Field in both breakout and fields, does the query still work? (Normalization should be taking care
  ;; of this) (#8760)
  (expect-with-non-timeseries-dbs
   :completed
   (-> (qp/process-query
        {:database (data/id)
         :type     :query
         :query    {:source-table (data/id :venues)
                    :breakout     [[:field-id (data/id :venues :price)]]
                    :fields       [["field_id" (data/id :venues :price)]]}})
       :status))
  )
