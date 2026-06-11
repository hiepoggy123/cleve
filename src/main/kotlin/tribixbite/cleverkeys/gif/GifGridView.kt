package tribixbite.cleverkeys.gif

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.coroutines.*
import tribixbite.cleverkeys.Defaults

/**
 * Manages a RecyclerView-based GIF grid with Coil image loading and pagination.
 */
class GifGridManager(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private val columns: Int = 3
) {
    private var gifList: List<Gif> = emptyList()
    private var currentCategory: GifCategory = GifCategory.RECENTLY_USED
    private var currentSearchQuery: String = ""
    private var currentPage: Int = 0
    private var totalItems: Int = 0

    private val database = GifDatabase.getInstance(context)
    private val adapter = GifRecyclerAdapter()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val provider: OnlineGifProvider by lazy {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        val source = prefs.getString("primary_gif_source", Defaults.PRIMARY_GIF_SOURCE) ?: Defaults.PRIMARY_GIF_SOURCE
        
        if (source.equals("giphy", ignoreCase = true)) {
            val key = prefs.getString("giphy_api_key", Defaults.GIPHY_API_KEY) ?: Defaults.GIPHY_API_KEY
            GiphyGifProvider(key)
        } else {
            val key = prefs.getString("tenor_api_key", Defaults.TENOR_API_KEY) ?: Defaults.TENOR_API_KEY
            TenorGifProvider(key)
        }
    }

    private val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizeBytes(32 * 1024 * 1024)
                .build()
        }
        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
        .crossfade(false)
        .build()

    var onGifSelected: ((Gif) -> Unit)? = null
    var onGifLongPress: ((Gif, View) -> Unit)? = null
    var onPaginationChanged: ((needsPagination: Boolean, currentPage: Int, totalPages: Int) -> Unit)? = null

    init {
        recyclerView.layoutManager = GridLayoutManager(context, columns)
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.itemAnimator = null

        scope.launch {
            loadCategory(GifCategory.RECENTLY_USED)
            if (gifList.isEmpty()) {
                loadCategory(GifCategory.ALL)
            }
        }
    }

    fun setCategory(category: GifCategory) {
        currentCategory = category
        currentSearchQuery = ""
        currentPage = 0
        scope.launch { loadCategory(category) }
    }

    fun search(query: String) {
        currentSearchQuery = query
        currentPage = 0
        scope.launch {
            if (query.isBlank()) {
                gifList = database.getRecentlyUsedGifs(50)
                totalItems = gifList.size
            } else {
                gifList = provider.search(query, ITEMS_PER_PAGE, 0)
                totalItems = if (gifList.size == ITEMS_PER_PAGE) ITEMS_PER_PAGE * 10 else gifList.size
            }
            withContext(Dispatchers.Main) {
                adapter.notifyDataSetChanged()
                recyclerView.scrollToPosition(0)
                notifyPagination()
            }
        }
    }

    fun getResultCount(): Int = gifList.size

    fun nextPage() {
        if (!hasNextPage()) return
        currentPage++
        scope.launch { reloadCurrentView() }
    }

    fun previousPage() {
        if (currentPage <= 0) return
        currentPage--
        scope.launch { reloadCurrentView() }
    }

    fun hasNextPage(): Boolean = (currentPage + 1) * ITEMS_PER_PAGE < totalItems
    fun hasPreviousPage(): Boolean = currentPage > 0

    private suspend fun loadCategory(category: GifCategory) {
        if (category == GifCategory.RECENTLY_USED) {
            gifList = database.getRecentlyUsedGifs(50)
            totalItems = gifList.size
        } else if (category == GifCategory.ALL) {
            gifList = provider.getTrending(ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
            totalItems = if (gifList.size == ITEMS_PER_PAGE) (currentPage + 2) * ITEMS_PER_PAGE else (currentPage * ITEMS_PER_PAGE) + gifList.size
        } else {
            val query = category.name.lowercase().replace("_", " ")
            gifList = provider.search(query, ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
            totalItems = if (gifList.size == ITEMS_PER_PAGE) (currentPage + 2) * ITEMS_PER_PAGE else (currentPage * ITEMS_PER_PAGE) + gifList.size
        }
        withContext(Dispatchers.Main) {
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
            notifyPagination()
        }
    }

    private suspend fun reloadCurrentView() {
        if (currentSearchQuery.isNotBlank()) {
            gifList = provider.search(currentSearchQuery, ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
            totalItems = if (gifList.size == ITEMS_PER_PAGE) (currentPage + 2) * ITEMS_PER_PAGE else (currentPage * ITEMS_PER_PAGE) + gifList.size
            withContext(Dispatchers.Main) {
                adapter.notifyDataSetChanged()
                recyclerView.scrollToPosition(0)
                notifyPagination()
            }
        } else {
            loadCategory(currentCategory)
        }
    }

    private fun notifyPagination() {
        val totalPages = if (totalItems <= 0) 1 else (totalItems + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        onPaginationChanged?.invoke(
            totalItems > ITEMS_PER_PAGE,
            currentPage + 1,
            totalPages
        )
    }

    fun destroy() {
        scope.cancel()
        imageLoader.shutdown()
    }

    private inner class GifViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView as ImageView

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val gif = gifList.getOrNull(pos) ?: return@setOnClickListener
                scope.launch { database.recordGifUsage(gif) }
                onGifSelected?.invoke(gif)
            }
            itemView.setOnLongClickListener { v ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val gif = gifList.getOrNull(pos) ?: return@setOnLongClickListener false
                onGifLongPress?.invoke(gif, v)
                true
            }
        }

        fun bind(gif: Gif) {
            if (gif.thumbnailUrl.isNotBlank()) {
                val request = ImageRequest.Builder(context)
                    .data(gif.thumbnailUrl)
                    .target(imageView)
                    .scale(Scale.FILL)
                    .size(THUMB_SIZE_PX)
                    .build()
                imageLoader.enqueue(request)
            } else {
                imageView.setImageDrawable(null)
            }
        }
    }

    private inner class GifRecyclerAdapter : RecyclerView.Adapter<GifViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifViewHolder {
            val imageView = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(80)
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                val pad = dpToPx(2)
                setPadding(pad, pad, pad, pad)
            }
            return GifViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: GifViewHolder, position: Int) {
            gifList.getOrNull(position)?.let { holder.bind(it) }
        }

        override fun getItemCount(): Int = gifList.size

        override fun onViewRecycled(holder: GifViewHolder) {
            holder.imageView.setImageDrawable(null)
        }
    }

    companion object {
        const val ITEMS_PER_PAGE = 50
        private const val THUMB_SIZE_PX = 200

        private fun dpToPx(dp: Int): Int {
            return (dp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
        }
    }
}
