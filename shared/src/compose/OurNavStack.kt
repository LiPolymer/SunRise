package ink.lipoly.app.sunrise.compose

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration
import ink.lipoly.app.sunrise.icons.ImportedIcons
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.getPolymorphicDescriptors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

object OurNavStack {

    interface INavNode : NavKey {
        val displayName : String
            get() = ""
        val icon : ImageVector?
            get() = null
    }

    @Serializable
    sealed interface Route : NavKey {
        @Serializable
        data object Overview : Route, INavNode {
            override val displayName: String
                get() = "总览"
            override val icon: ImageVector
                get() = Icons.Rounded.PlayArrow
        }

        @Serializable
        data object Equalizer : Route, INavNode {
            override val displayName: String
                get() = "均衡器"
            override val icon: ImageVector
                get() = ImportedIcons.Tune
        }

        @Serializable
        data object Settings : Route, INavNode {
            override val displayName: String
                get() = "设定"
            override val icon: ImageVector
                get() = Icons.Filled.Settings
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val routeModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclassesOfSealed<Route>()
        }
    }

    private val routeJson = Json { serializersModule = routeModule }

    private val navConfig = SavedStateConfiguration {
        serializersModule = routeModule
    }

    @OptIn(ExperimentalSerializationApi::class)
    val navigatableNodes: List<INavNode> by lazy {
        val serializer = PolymorphicSerializer(NavKey::class)
        routeModule
            .getPolymorphicDescriptors(serializer.descriptor)
            .asSequence()
            .filter { it.kind == StructureKind.OBJECT }
            .map { descriptor ->
                val jsonObj = buildJsonObject {
                    put("type", JsonPrimitive(descriptor.serialName))
                }
                routeJson.decodeFromJsonElement(serializer,jsonObj)
            }
            .filterIsInstance<INavNode>()
            .toList()
    }

    @Composable
    fun rememberIt(): NavBackStack<NavKey> {
        return rememberNavBackStack(navConfig, Route.Overview)
    }

    fun NavBackStack<NavKey>.back() {
        if (size > 1) removeAt(lastIndex)
    }

    val NavBackStack<NavKey>.activeNode: INavNode?
        get() = lastOrNull { it is INavNode } as? INavNode

    @Composable
    context(scope: RowScope)
    fun List<INavNode>.createNavigationBarItems(selected: NavKey?, onNavigate: (INavNode) -> Unit) {
        forEach {
            scope.NavigationBarItem(
                selected = it == selected,
                onClick = { if (it != selected) onNavigate(it) },
                icon = {
                    it.icon?.let { iv ->
                        Icon(iv, contentDescription = it.displayName)
                    }
                },
                label = {
                    Text(it.displayName)
                }
            )
        }
    }
}