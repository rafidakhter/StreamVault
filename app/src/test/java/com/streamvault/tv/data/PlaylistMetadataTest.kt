package com.streamvault.tv.data

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class PlaylistMetadataTest {
    private fun parse(text: String) = M3uParser.parse(StringReader(text.trimIndent()).buffered(), "test")

    @Test fun requestsExtendedMetadataWithoutChangingCredentialsOrOutput() {
        assertEquals(
            "https://example.com/get.php?username=user&password=a%26b&output=ts&type=m3u_plus",
            metadataPlaylistUrl("https://example.com/get.php?username=user&password=a%26b&type=m3u&output=ts"),
        )
    }

    @Test fun leavesOtherPlaylistLinksAlone() {
        for (url in listOf("https://example.com/list.m3u?type=m3u", "https://example.com/get.php?type=m3u_plus")) {
            assertEquals(url, metadataPlaylistUrl(url))
        }
    }

    @Test fun plainPlaylistUsesStreamPathsAndEpisodeNames() {
        val channels = parse("""
            #EXTM3U
            #EXTINF:-1,News
            https://example.com/live/u/p/1.ts
            #EXTINF:-1,A Movie
            https://example.com/movie/u/p/2
            #EXTINF:-1,A Show S02E03
            https://example.com/stream/3
        """)
        assertEquals(listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES), channels.map { it.type })
        assertEquals("A Show", channels.last().seriesName)
        assertEquals(2, channels.last().season)
        assertEquals(3, channels.last().episode)
    }

    @Test fun providerGroupsArePreservedAndLivePathsOutrankGroupKeywords() {
        val library = LibraryBuilder.build(parse("""
            #EXTM3U
            #EXTINF:-1 group-title="Movie Channels",Cinema TV
            https://example.com/live/u/p/1.ts
            #EXTINF:-1 group-title="English Movies",A Movie
            https://example.com/2
            #EXTINF:-1 group-title="English Series",A Show S01E01
            https://example.com/3
        """))
        assertEquals("Movie Channels", library.liveGroups.single().first)
        assertEquals("English Movies", library.movieGroups.single().first)
        assertEquals("English Series", library.seriesGroups.single().first)
    }
}
