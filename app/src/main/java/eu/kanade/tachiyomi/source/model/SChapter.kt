package eu.kanade.tachiyomi.source.model

import java.io.Serializable

interface SChapter : Serializable {

    var url: String

    var name: String

    var date_upload: Long

    var chapter_number: Float

    var scanlator: String?

    var manga_id: Long?

    fun copyFrom(other: SChapter) {
        url = other.url
        name = other.name
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        manga_id = other.manga_id
    }

    companion object {
        fun create(): SChapter {
            return SChapterImpl()
        }
    }
}

class SChapterImpl : SChapter {
    override var url: String = ""
    override var name: String = ""
    override var date_upload: Long = 0
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
    override var manga_id: Long? = null
}
