@file:OptIn(InternalResourceApi::class)

package kmp_history_search.composeapp.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.ResourceContentHash
import org.jetbrains.compose.resources.ResourceItem

private const val MD: String = "composeResources/kmp_history_search.composeapp.generated.resources/"

@delegate:ResourceContentHash(-2_057_083_145)
internal val Res.drawable.chrome: DrawableResource by lazy {
      DrawableResource("drawable:chrome", setOf(
        ResourceItem(setOf(), "${MD}drawable/chrome.svg", -1, -1),
      ))
    }

@delegate:ResourceContentHash(525_823_846)
internal val Res.drawable.chromium: DrawableResource by lazy {
      DrawableResource("drawable:chromium", setOf(
        ResourceItem(setOf(), "${MD}drawable/chromium.svg", -1, -1),
      ))
    }

@delegate:ResourceContentHash(379_089_144)
internal val Res.drawable.compose_multiplatform: DrawableResource by lazy {
      DrawableResource("drawable:compose_multiplatform", setOf(
        ResourceItem(setOf(), "${MD}drawable/compose-multiplatform.xml", -1, -1),
      ))
    }

@delegate:ResourceContentHash(112_398_629)
internal val Res.drawable.zen: DrawableResource by lazy {
      DrawableResource("drawable:zen", setOf(
        ResourceItem(setOf(), "${MD}drawable/zen.svg", -1, -1),
      ))
    }

@InternalResourceApi
internal fun _collectJvmMainDrawable0Resources(map: MutableMap<String, DrawableResource>) {
  map.put("chrome", Res.drawable.chrome)
  map.put("chromium", Res.drawable.chromium)
  map.put("compose_multiplatform", Res.drawable.compose_multiplatform)
  map.put("zen", Res.drawable.zen)
}
