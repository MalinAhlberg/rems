-- :name get-catalogue-items :? :*
SELECT ci.id, ci.title, res.resid
FROM catalogue_item ci
LEFT OUTER JOIN resource res ON (ci.resid = res.id)

-- :name get-catalogue-item :? :1
SELECT ci.id, ci.title, res.resid
FROM catalogue_item ci
LEFT OUTER JOIN resource res ON (ci.resid = res.id)
WHERE ci.id = :id

-- :name create-catalogue-item! :insert
-- :doc Create a single catalogue item
INSERT INTO catalogue_item
(title, formid, resid)
VALUES (:title, :form, :resid)

-- :name create-resource! :insert
-- :doc Create a single resource
INSERT INTO resource
(id, resid, prefix, modifieruserid)
VALUES (:id, :resid, :prefix, :modifieruserid)

-- :name get-database-name :? :1
SELECT current_database()

-- :name get-catalogue-item-localizations :? :*
SELECT catid, langcode, title
FROM catalogue_item_localization

-- :name get-forms :? :*
SELECT
  meta.id as metaid,
  form.id as formid,
  meta.title as metatitle,
  form.title as formtitle,
  meta.visibility as metavisibility,
  form.visibility as formvisibility,
  langcode
FROM application_form_meta meta
LEFT OUTER JOIN application_form_meta_map metamap ON meta.id = metamap.metaFormId
LEFT OUTER JOIN application_form form ON form.id = metamap.formId

-- :name get-form-for-catalogue-item :? :1
SELECT
  meta.id as metaid,
  form.id as formid,
  meta.title as metatitle,
  form.title as formtitle,
  meta.visibility as metavisibility,
  form.visibility as formvisibility,
  langcode
FROM catalogue_item ci
LEFT OUTER JOIN application_form_meta meta ON ci.formId = meta.id
LEFT OUTER JOIN application_form_meta_map metamap ON meta.id = metamap.metaFormId
LEFT OUTER JOIN application_form form ON form.id = metamap.formId
WHERE ci.id = :id
  AND (langcode = :lang
       OR langcode is NULL) -- nonlocalized form

-- :name get-form-items :? :*
SELECT
  item.id,
  item.title,
  inputprompt,
  formitemoptional,
  type,
  value,
  itemorder,
  tooltip,
  item.visibility
FROM application_form form
LEFT OUTER JOIN application_form_item_map itemmap ON form.id = itemmap.formId
LEFT OUTER JOIN application_form_item item ON item.id = itemmap.formItemId
WHERE form.id = :id
ORDER BY itemorder

-- :name create-form! :insert
INSERT INTO application_form
(title, modifierUserId, ownerUserId, visibility)
VALUES
(:title, :user, :user, 'public')

-- :name create-form-meta! :insert
INSERT INTO application_form_meta
(title, ownerUserId, modifierUserId, visibility)
VALUES
(:title, :user, :user, 'public')

-- :name link-form-meta! :insert
INSERT INTO application_form_meta_map
(metaFormId, formId, langcode, modifierUserId)
VALUES
(:meta, :form, :lang, :user)

-- :name create-form-item! :insert
INSERT INTO application_form_item
(title, type, inputPrompt, value, modifierUserId, ownerUserId, visibility)
VALUES
(:title, CAST (:type as itemtype), :inputprompt, :value, :user, :user, 'public')

-- :name link-form-item! :insert
INSERT INTO application_form_item_map
(formId, formItemId, modifierUserId, itemOrder)
VALUES
(:form, :item, :user, :itemorder)

-- :name create-application! :insert
-- TODO: what is fnlround?
INSERT INTO catalogue_item_application
(catId, applicantUserId, fnlround)
VALUES
(:item, :user, 0)
RETURNING id

-- :name update-application-state! :!
INSERT INTO catalogue_item_application_state
(catAppId, modifierUserId, curround, state)
VALUES
(:id, :user, 0, CAST (:state as application_state))
ON CONFLICT (catAppId)
DO UPDATE
SET (modifierUserId, curround, state) = (:user, 0, CAST (:state as application_state))

-- :name get-applications :? :*
SELECT
  app.id, app.catId, app.applicantUserId, state.state
FROM catalogue_item_application app
LEFT OUTER JOIN catalogue_item_application_state state ON app.id = state.catAppId

-- :name save-field-value! :!
INSERT INTO application_text_values
(catAppId, modifierUserId, value, formMapId)
VALUES
(:application, :user, :value,
 (SELECT id FROM application_form_item_map
  WHERE formId = :form AND formItemId = :item))
ON CONFLICT (catAppId, formMapId)
DO UPDATE
SET (modifierUserId, value) = (:user, :value)

-- :name clear-field-value! :!
DELETE FROM application_text_values
WHERE catAppId = :application
  AND formMapId = (SELECT id FROM application_form_item_map
                   WHERE formId = :form AND formItemId = :item)

-- :name get-field-value :? :n
SELECT
  value
FROM application_text_values textvalues
LEFT OUTER JOIN application_form_item_map itemmap ON textvalues.formMapId = itemmap.id
WHERE textvalues.catAppId = :application
  AND itemmap.formItemId = :item
  AND itemmap.formId = :form
