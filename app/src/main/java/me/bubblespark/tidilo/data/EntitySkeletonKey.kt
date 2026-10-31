package me.bubblespark.tidilo.data

import java.text.Normalizer
import me.bubblespark.tidilo.data.db.bookmarkSearchKey

/** Produces entity identity while preserving meaningful punctuation such as '+' and '#'. */
fun entitySkeletonKey(value: String): String =
    bookmarkSearchKey(Normalizer.normalize(value, Normalizer.Form.NFKC))
        .filterNot { it.isWhitespace() || it == '.' || it == '_' || it == '-' }
