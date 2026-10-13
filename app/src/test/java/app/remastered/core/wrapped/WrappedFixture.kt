package app.remastered.core.wrapped

import app.remastered.core.warehouse.Wrapped
import app.remastered.warehouse.WrappedDto
import app.remastered.warehouse.toModel
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A synthetic Wrapped in the web app's fixture shape (`{ data, artistImages, audio }`), decoded the
 * way the app decodes /api/wrapped. Invented artists and numbers: the web repo's own fixture is a
 * real listening history, so it stays out of this repo.
 */
object WrappedFixture {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    fun raw(): JsonObject {
        val text = checkNotNull(javaClass.getResource("/wrapped/fixture.json")).readText()
        return json.parseToJsonElement(text).jsonObject.getValue("data").jsonObject
    }

    fun load(edit: (JsonObject) -> JsonObject = { it }): Wrapped =
        json.decodeFromJsonElement(WrappedDto.serializer(), edit(raw())).toModel(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
}
