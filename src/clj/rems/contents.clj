(ns rems.contents
  (:require [hiccup.element :refer [link-to image]]
            [rems.cart :as cart]
            [rems.context :as context]
            [rems.db.core :as db]))

(defn login [context]
  [:div.jumbotron
   [:h2 "Login"]
   [:p "Login by using your Haka credentials"]
   (link-to (str context "/Shibboleth.sso/Login") (image {:class "login-btn"} "/img/haka_landscape_large.gif"))])

(defn about []
  [:p "this is the story of rems... work in progress"])

;; TODO duplication between cart and catalogue to be factored out

(defn cart-item [item]
  [:tr
   [:td {:data-th "Resource in cart"} (:title item)]
   [:td {:data-th ""}]])

(defn cart-list [items]
  (when-not (empty? items)
    [:table.ctlg-table
     [:tr
      [:th "Resource in cart"]
      [:th ""]]
     (for [item (sort-by :title items)]
       (cart-item item))]))

(defn urn-catalogue-item? [{:keys [resid]}]
  (and resid (.startsWith resid "http://urn.fi")))

(defn catalogue-item [item]
  (let [resid (:resid item)
        title (:title item)
        component (if (urn-catalogue-item? item)
                    [:a {:href resid :target :_blank} title]
                    title)]
    [:tr
     [:td {:data-th "Resource"} component]
     [:td {:data-th ""} (cart/add-to-cart-button item)]]))

(defn catalogue-list [items]
  [:table.ctlg-table
   [:tr
    [:th "Resource"]
    [:th ""]]
   (for [item (sort-by :title items)]
     (catalogue-item item))])

(defn catalogue []
  (list
   (cart-list (cart/get-cart-items))
   (catalogue-list (db/get-catalogue-items))))