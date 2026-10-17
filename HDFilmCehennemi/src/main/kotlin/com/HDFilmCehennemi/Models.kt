package com.HDFilmCehennemi

data class LinkData(
    val tmdbId: Int,
    val isTv: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
    val imdb: String? = null,
    val year: Int? = null,
    val titles: List<String> = emptyList()
)

data class TmdbList(val results: List<TmdbItem>? = null)

data class TmdbItem(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val media_type: String? = null,
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null
)

data class TmdbGenre(val name: String? = null)
data class TmdbExternalIds(val imdb_id: String? = null)

data class TmdbCast(
    val name: String? = null,
    val character: String? = null,
    val profile_path: String? = null
)

data class TmdbCredits(val cast: List<TmdbCast>? = null)

data class TmdbVideo(
    val site: String? = null,
    val type: String? = null,
    val key: String? = null
)

data class TmdbVideos(val results: List<TmdbVideo>? = null)

data class TmdbSeasonInfo(val season_number: Int? = null)

data class TmdbDetail(
    val id: Int? = null,
    val title: String? = null,
    val original_title: String? = null,
    val name: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val imdb_id: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre>? = null,
    val external_ids: TmdbExternalIds? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val seasons: List<TmdbSeasonInfo>? = null
)

data class TmdbEpisode(
    val episode_number: Int? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val air_date: String? = null
)

data class TmdbSeason(
    val season_number: Int? = null,
    val episodes: List<TmdbEpisode>? = null
)
