package com.vortex.player.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.libraryDataStore by preferencesDataStore("vortex_library")

enum class SortField(val label: String) {
    DATE("FECHA"),
    NAME("NOMBRE"),
    DURATION("DURACIÓN"),
    SIZE("TAMAÑO"),
    RESOLUTION("RESOLUCIÓN")
}

enum class ViewMode { GRID, LIST }

data class LibraryPrefs(
    val sortField: SortField = SortField.DATE,
    val descending: Boolean = true,
    val viewMode: ViewMode = ViewMode.GRID,
    /**
     * Reescanear solo cuando el sistema avisa de que los medios han cambiado. Se puede
     * apagar para quien tenga decenas de miles de ficheros y prefiera controlar cuándo
     * se paga ese coste.
     */
    val autoRefresh: Boolean = true
)

/** Cómo quiere el usuario ver su biblioteca. Se recuerda entre sesiones. */
object LibraryPreferences {

    private val SORT_FIELD = stringPreferencesKey("sort_field")
    private val SORT_DESC = booleanPreferencesKey("sort_desc")
    private val VIEW_MODE = stringPreferencesKey("view_mode")
    private val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")

    fun observe(context: Context): Flow<LibraryPrefs> =
        context.libraryDataStore.data.map { prefs ->
            LibraryPrefs(
                sortField = prefs[SORT_FIELD]
                    ?.let { name -> runCatching { SortField.valueOf(name) }.getOrNull() }
                    ?: SortField.DATE,
                descending = prefs[SORT_DESC] ?: true,
                viewMode = prefs[VIEW_MODE]
                    ?.let { name -> runCatching { ViewMode.valueOf(name) }.getOrNull() }
                    ?: ViewMode.GRID,
                autoRefresh = prefs[AUTO_REFRESH] ?: true
            )
        }

    suspend fun setAutoRefresh(context: Context, enabled: Boolean) {
        context.libraryDataStore.edit { it[AUTO_REFRESH] = enabled }
    }

    suspend fun setSort(context: Context, field: SortField, descending: Boolean) {
        context.libraryDataStore.edit {
            it[SORT_FIELD] = field.name
            it[SORT_DESC] = descending
        }
    }

    suspend fun setViewMode(context: Context, mode: ViewMode) {
        context.libraryDataStore.edit { it[VIEW_MODE] = mode.name }
    }
}

/**
 * Ordena aplicando el criterio elegido. El desempate siempre es el nombre: sin él, dos
 * ficheros con la misma fecha bailarían de sitio entre recomposiciones.
 */
fun List<MediaEntry>.sortedBy(prefs: LibraryPrefs): List<MediaEntry> {
    val comparator: Comparator<MediaEntry> = when (prefs.sortField) {
        SortField.DATE -> compareBy { it.dateAddedSec }
        SortField.NAME -> compareBy { it.title.ifBlank { it.displayName }.lowercase() }
        SortField.DURATION -> compareBy { it.durationMs }
        SortField.SIZE -> compareBy { it.sizeBytes }
        SortField.RESOLUTION -> compareBy { minOf(it.width, it.height) }
    }
    val tieBreaker = compareBy<MediaEntry> { it.displayName.lowercase() }
    val full = comparator.then(tieBreaker)
    return if (prefs.descending) sortedWith(full.reversed()) else sortedWith(full)
}
