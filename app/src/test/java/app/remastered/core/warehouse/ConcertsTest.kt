package app.remastered.core.warehouse

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

private const val ADA = "0gxyHStUsqpMadRV0Di1Qt"

class ConcertsTest {
    private val today = LocalDate.of(2026, 10, 23)

    private fun gig(name: String, date: LocalDate?, vararg artists: ConcertArtist, status: String? = "onsale") =
        Concert(name, artists.toList(), date, null, null, null, null, status, null, null)

    private val concerts = Concerts(
        emptyList(),
        listOf(
            gig("past", today.minusDays(1), ConcertArtist(ADA, "Ada Lark")),
            gig("tonight", today, ConcertArtist(ADA, "Ada Lark")),
            gig("cancelled", today.plusDays(3), ConcertArtist(ADA, "Ada Lark"), status = "cancelled"),
            gig("by name", today.plusDays(5), ConcertArtist("name:ada lark", "ADA LARK")),
            gig("someone else", today.plusDays(6), ConcertArtist(null, "Bea Moss")),
            gig("to be announced", null, ConcertArtist(null, "Bea Moss"), ConcertArtist(ADA, "Ada Lark")),
        ),
    )

    @Test fun `upcoming drops past and cancelled gigs, keeps undated ones`() {
        assertEquals(listOf("tonight", "by name", "someone else", "to be announced"), concerts.upcoming(today).map { it.name })
    }

    @Test fun `an artist's gigs match by id or, failing that, by name`() {
        assertEquals(listOf("tonight", "by name", "to be announced"), concerts.upcoming(today, ADA, "Ada Lark").map { it.name })
        assertEquals(listOf("someone else", "to be announced"), concerts.upcoming(today, null, "bea moss").map { it.name })
        assertEquals(emptyList<String>(), concerts.upcoming(today, "1111111111111111111111", "Nobody").map { it.name })
    }
}
