package eu.akoos.photos.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhotoWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = PhotoWidget()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Trigger an immediate update for each (re-)added widget
        appWidgetIds.forEach { id ->
            PhotoWidgetUpdateWorker.enqueueImmediate(context, id)
            PhotoWidgetUpdateWorker.enqueueOrReplace(context, id)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { id ->
            PhotoWidgetUpdateWorker.cancel(context, id)
            scope.launch { PhotoWidgetUpdater.cleanup(context, id) }
        }
    }
}
