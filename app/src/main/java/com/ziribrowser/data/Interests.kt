package com.ziribrowser.data

import com.ziribrowser.R

/** A site suggested for an interest. The name is shown under its icon. */
data class InterestSite(val name: String, val url: String)

/** A news/RSS feed the Home screen can show headlines from. All feeds are public and loaded over HTTPS. */
data class InterestFeed(val source: String, val url: String)

/**
 * Topics a person can pick at first launch (and change later). Each one brings a few well-known sites for
 * "Popular for you" and a few public feeds for "Latest for you". The lists are fixed, not personalised by any server.
 */
enum class Interest(@androidx.annotation.StringRes val labelRes: Int, val emoji: String, val sites: List<InterestSite>, val feeds: List<InterestFeed>) {
    GAMING(
        R.string.interest_gaming, "🎮",
        listOf(
            InterestSite("Twitch", "https://www.twitch.tv"), InterestSite("Steam", "https://store.steampowered.com"),
            InterestSite("IGN", "https://www.ign.com"), InterestSite("Epic Games", "https://store.epicgames.com"),
            InterestSite("Xbox", "https://www.xbox.com"), InterestSite("PlayStation", "https://www.playstation.com"),
            InterestSite("itch.io", "https://itch.io"), InterestSite("GameSpot", "https://www.gamespot.com"),
        ),
        listOf(InterestFeed("GameSpot", "https://www.gamespot.com/feeds/news/"), InterestFeed("Kotaku", "https://www.kotaku.com/rss")),
    ),
    ANIME(
        R.string.interest_anime, "🍥",
        listOf(
            InterestSite("Crunchyroll", "https://www.crunchyroll.com"), InterestSite("MyAnimeList", "https://myanimelist.net"),
            InterestSite("AniList", "https://anilist.co"), InterestSite("Anime News Network", "https://www.animenewsnetwork.com"),
            InterestSite("Pixiv", "https://www.pixiv.net"), InterestSite("Bilibili", "https://www.bilibili.com"),
        ),
        listOf(InterestFeed("Anime News Network", "https://www.animenewsnetwork.com/all/rss.xml"), InterestFeed("MyAnimeList", "https://myanimelist.net/rss/news.xml")),
    ),
    TECHNOLOGY(
        R.string.interest_technology, "💻",
        listOf(
            InterestSite("The Verge", "https://www.theverge.com"), InterestSite("GitHub", "https://github.com"),
            InterestSite("Hacker News", "https://news.ycombinator.com"), InterestSite("TechCrunch", "https://techcrunch.com"),
            InterestSite("Stack Overflow", "https://stackoverflow.com"), InterestSite("Ars Technica", "https://arstechnica.com"),
            InterestSite("WIRED", "https://www.wired.com"), InterestSite("Product Hunt", "https://www.producthunt.com"),
        ),
        listOf(InterestFeed("The Verge", "https://www.theverge.com/rss/index.xml"), InterestFeed("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index")),
    ),
    SCIENCE(
        R.string.interest_science, "🔬",
        listOf(
            InterestSite("NASA", "https://www.nasa.gov"), InterestSite("Nature", "https://www.nature.com"),
            InterestSite("Science", "https://www.science.org"), InterestSite("Scientific American", "https://www.scientificamerican.com"),
            InterestSite("National Geographic", "https://www.nationalgeographic.com"), InterestSite("ScienceDaily", "https://www.sciencedaily.com"),
            InterestSite("Space.com", "https://www.space.com"), InterestSite("Khan Academy", "https://www.khanacademy.org"),
        ),
        listOf(InterestFeed("ScienceDaily", "https://www.sciencedaily.com/rss/all.xml"), InterestFeed("NASA", "https://www.nasa.gov/feed/")),
    ),
    ARTS(
        R.string.interest_arts, "🎨",
        listOf(
            InterestSite("Behance", "https://www.behance.net"), InterestSite("Dribbble", "https://dribbble.com"),
            InterestSite("DeviantArt", "https://www.deviantart.com"), InterestSite("ArtStation", "https://www.artstation.com"),
            InterestSite("Pinterest", "https://www.pinterest.com"), InterestSite("Colossal", "https://www.thisiscolossal.com"),
            InterestSite("Unsplash", "https://unsplash.com"), InterestSite("Hyperallergic", "https://hyperallergic.com"),
        ),
        listOf(InterestFeed("Colossal", "https://www.thisiscolossal.com/feed/"), InterestFeed("Hyperallergic", "https://hyperallergic.com/feed/")),
    ),
    SPORTS(
        R.string.interest_sports, "⚽",
        listOf(
            InterestSite("ESPN", "https://www.espn.com"), InterestSite("BBC Sport", "https://www.bbc.com/sport"),
            InterestSite("Sky Sports", "https://www.skysports.com"), InterestSite("NBA", "https://www.nba.com"),
            InterestSite("FIFA", "https://www.fifa.com"), InterestSite("ESPNcricinfo", "https://www.espncricinfo.com"),
            InterestSite("UFC", "https://www.ufc.com"), InterestSite("Formula 1", "https://www.formula1.com"),
        ),
        listOf(InterestFeed("BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml"), InterestFeed("Sky Sports", "https://www.skysports.com/rss/12040")),
    ),
    MUSIC(
        R.string.interest_music, "🎧",
        listOf(
            InterestSite("Spotify", "https://open.spotify.com"), InterestSite("SoundCloud", "https://soundcloud.com"),
            InterestSite("Pitchfork", "https://pitchfork.com"), InterestSite("Bandcamp", "https://bandcamp.com"),
            InterestSite("Genius", "https://genius.com"), InterestSite("Last.fm", "https://www.last.fm"),
        ),
        listOf(InterestFeed("Pitchfork", "https://pitchfork.com/feed/feed-news/rss")),
    ),
    MOVIES(
        R.string.interest_movies, "🎬",
        listOf(
            InterestSite("IMDb", "https://www.imdb.com"), InterestSite("Rotten Tomatoes", "https://www.rottentomatoes.com"),
            InterestSite("Netflix", "https://www.netflix.com"), InterestSite("Letterboxd", "https://letterboxd.com"),
            InterestSite("TMDB", "https://www.themoviedb.org"), InterestSite("Variety", "https://variety.com"),
        ),
        listOf(InterestFeed("Variety", "https://variety.com/feed/")),
    ),
    NEWS(
        R.string.interest_news, "📰",
        listOf(
            InterestSite("BBC News", "https://www.bbc.com/news"), InterestSite("CNN", "https://www.cnn.com"),
            InterestSite("Reuters", "https://www.reuters.com"), InterestSite("The Guardian", "https://www.theguardian.com"),
            InterestSite("The New York Times", "https://www.nytimes.com"), InterestSite("Al Jazeera", "https://www.aljazeera.com"),
        ),
        listOf(InterestFeed("BBC News", "https://feeds.bbci.co.uk/news/rss.xml"), InterestFeed("The Guardian", "https://www.theguardian.com/world/rss")),
    ),
    FINANCE(
        R.string.interest_finance, "📈",
        listOf(
            InterestSite("Bloomberg", "https://www.bloomberg.com"), InterestSite("CNBC", "https://www.cnbc.com"),
            InterestSite("Yahoo Finance", "https://finance.yahoo.com"), InterestSite("TradingView", "https://www.tradingview.com"),
            InterestSite("Investopedia", "https://www.investopedia.com"), InterestSite("CoinMarketCap", "https://coinmarketcap.com"),
        ),
        listOf(InterestFeed("CNBC", "https://www.cnbc.com/id/100003114/device/rss/rss.html")),
    ),
    TRAVEL(
        R.string.interest_travel, "✈️",
        listOf(
            InterestSite("Booking.com", "https://www.booking.com"), InterestSite("Tripadvisor", "https://www.tripadvisor.com"),
            InterestSite("Lonely Planet", "https://www.lonelyplanet.com"), InterestSite("Airbnb", "https://www.airbnb.com"),
            InterestSite("Skyscanner", "https://www.skyscanner.com"), InterestSite("Condé Nast Traveler", "https://www.cntraveler.com"),
        ),
        listOf(InterestFeed("Condé Nast Traveler", "https://www.cntraveler.com/feed/rss")),
    ),
    FOOD(
        R.string.interest_food, "🍜",
        listOf(
            InterestSite("Allrecipes", "https://www.allrecipes.com"), InterestSite("Serious Eats", "https://www.seriouseats.com"),
            InterestSite("Tasty", "https://tasty.co"), InterestSite("Food Network", "https://www.foodnetwork.com"),
            InterestSite("Epicurious", "https://www.epicurious.com"), InterestSite("Bon Appétit", "https://www.bonappetit.com"),
        ),
        listOf(InterestFeed("Bon Appétit", "https://www.bonappetit.com/feed/rss"), InterestFeed("Epicurious", "https://www.epicurious.com/feed/rss")),
    ),
    ;

    val label: String get() = com.ziribrowser.ziriString(labelRes)

    companion object {
        fun parse(names: Set<String>): Set<Interest> = names.mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet()

        /** Up to [limit] sites, taking turns between the chosen interests so each one is represented. */
        fun popularFor(chosen: Set<Interest>, limit: Int = 10): List<InterestSite> {
            val lists = entries.filter { it in chosen }.map { it.sites }
            val out = ArrayList<InterestSite>()
            var i = 0
            while (out.size < limit && lists.any { i < it.size }) {
                lists.forEach { l -> if (i < l.size && out.size < limit && out.none { it.url == l[i].url }) out += l[i] }
                i++
            }
            return out
        }
    }
}
