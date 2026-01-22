package org.benesv.history.api.browser

import org.jetbrains.exposed.v1.core.Table

/**
 * Chrome browser history database schema.
 */
object ChromeUrls : Table("urls") {
    val id = integer("id")
    val url = text("url")
    val title = text("title")
    val lastVisitTime = long("last_visit_time")
    val visitCount = integer("visit_count")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Chrome browser favicons database schema.
 */
object ChromeFavicons : Table("icon_mapping") {
    val id = integer("id")
    val page_url = text("page_url")
    val icon_id = integer("icon_id").references(ChromeFaviconBitmaps.iconId)
    val page_url_type = text("page_url_type")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Chrome browser favicon bitmap database schema.
 */
object ChromeFaviconBitmaps : Table("favicon_bitmaps") {
    val iconId = integer("icon_id")
    val imageData = blob("image_data")
    override val primaryKey = PrimaryKey(iconId)
}


/**
 * Chrome browser visits tracking database schema.
 */
object ChromeVisits : Table("visits") {
    val urlId = integer("url") // references ChromeUrls.id
}

/**
 * Firefox/Zen browser places the database schema (full schema with history data).
 */
object MozPlaces : Table("moz_places") {
    val id = integer("id")
    val url = text("url")
    val title = text("title").nullable()
    val lastVisitDate = long("last_visit_date")
    val visitCount = integer("visit_count")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Firefox/Zen browser places database schema (minimal schema for deletion).
 * Used when only id and url are needed.
 */
object MozPlacesMinimal : Table("moz_places") {
    val id = integer("id")
    val url = text("url")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Firefox/Zen browser history visits database schema.
 */
object MozHistoryVisits : Table("moz_historyvisits") {
    val placeId = integer("place_id") // references MozPlaces.id
}

/**
 * Firefox/Zen browser favicons database schema.
 */
object MozFavicons : Table("moz_icons") {
    val id = integer("id")
    val icon_url = blob("icon_url")
    val data = text("data")
    override val primaryKey = PrimaryKey(id)
}
