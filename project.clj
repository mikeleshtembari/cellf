(defproject cellf "0.1.0-SNAPSHOT"
  :description "Find yourself"
  :url "http://oxism.com/cellf"
  :license {:name "The MIT License (MIT)"
            :url "http://opensource.org/licenses/mit-license.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.40"]
                 [org.clojure/core.async "0.2.374"]
                 [org.omcljs/om "0.9.0"]]

  :plugins [[lein-cljsbuild "1.1.3"]
            [lein-figwheel "0.5.2"]]

  :source-paths ["src" "test"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                    "resources/test/compiled.js"
                                    "target"]

  :cljsbuild {:builds {:dev {:source-paths ["src"]
                             :figwheel {}
                             :compiler {:main cellf.core
                                        :asset-path "js/compiled/out"
                                        :output-to "resources/public/js/compiled/cellf.js"
                                        :output-dir "resources/public/js/compiled/out"
                                        :optimizations :none
                                        :source-map true
                                        :source-map-timestamp true
                                        :cache-analysis true}}

                       :min {:source-paths ["src"]
                             :compiler {:output-to "cellf.min.js"
                                        :main cellf.core
                                        :optimizations :advanced
                                        :pretty-print false
                                        :externs ["resources/externs/gif.ext.js"]}}

                       :test {:source-paths ["src" "test"]
                              :notify-command ["phantomjs" "resources/test/test.js" "resources/test/test.html"]
                              :compiler {:output-to "resources/test/compiled.js"
                                         :optimizations :simple
                                         :pretty-print true}}}

              :test-commands {"test" ["phantomjs" "resources/test/test.js" "resources/test/test.html"]}}

  :figwheel {:css-dirs ["resources/public/css"]})
