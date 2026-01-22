package org.benesv.history.api.app

import org.benesv.history.model.Favicon
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object Favicons : IntIdTable("favicons") {
    val url = text("url").uniqueIndex()
    val imageData = blob("image_data")
}

object History : IntIdTable("history") {
    val browser = text("browser") // BrowserType as string
    val profile = text("profile")
    val url = text("url").uniqueIndex()
    val title = text("title")
    val lastVisit = long("last_visit")
    val visitCount = integer("visit_count")
    val domain = text("domain")
    val favicon = reference(
        "favicon",
        Favicons,
        onDelete = ReferenceOption.SET_NULL
    ).nullable()
}

class FaviconEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<FaviconEntity>(Favicons)

    var url by Favicons.url
    var imageData by Favicons.imageData

    fun toModel(): Favicon = Favicon(
        id = id.value,
        url = url,
        imageData = imageData.bytes
    )
}

class HistoryEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<HistoryEntity>(History)

    var browser by History.browser
    var profile by History.profile
    var url by History.url
    var title by History.title
    var lastVisit by History.lastVisit
    var visitCount by History.visitCount
    var domain by History.domain
    var favicon by FaviconEntity optionalReferencedOn History.favicon
}

// User token suggestions storage
object Tokens : IntIdTable("tokens") {
    val text = text("text").uniqueIndex()
    val frequency = integer("frequency").default(0)
    val lastUsed = long("last_used").default(0L)
}

class TokenEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TokenEntity>(Tokens)

    var text by Tokens.text
    var frequency by Tokens.frequency
    var lastUsed by Tokens.lastUsed
}
