package com.fanlens.prototype.ui.catalogue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fanlens.prototype.data.CatalogRepository
import com.fanlens.prototype.model.ProductWithPhotos
import com.fanlens.prototype.ui.FanLensColors

/**
 * Full product record with a swipeable gallery of every stored photo, cover first.
 */
@Composable
fun ProductDetailScreen(
    repository: CatalogRepository,
    productId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val loaded by produceState<ProductWithPhotos?>(initialValue = null, productId) {
        value = repository.productWithPhotos(productId)
    }

    val details = loaded
    Column(
        Modifier
            .fillMaxSize()
            .background(FanLensColors.Paper)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back", color = FanLensColors.InkMuted) }
            Spacer(Modifier.weight(1f))
            if (details != null) {
                TextButton(onClick = { onEdit(productId) }) {
                    Text("Edit", color = FanLensColors.BrandRed, fontWeight = FontWeight.Bold)
                }
            }
        }
        HorizontalDivider(color = FanLensColors.Rule)

        if (details == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This product is no longer here.", color = FanLensColors.InkMuted)
            }
            return@Column
        }

        // Customers see catalogue photos where they exist; a product with only
        // shop photos still shows those rather than an empty gallery.
        val ordered = remember(details) {
            val gallery = details.galleryPhotos
            val cover = details.coverPhoto?.takeIf { c -> gallery.any { it.id == c.id } }
            if (cover == null) gallery
            else listOf(cover) + gallery.filterNot { it.id == cover.id }
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (ordered.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(FanLensColors.PaperRaised),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No photos yet", color = FanLensColors.InkMuted)
                }
            } else {
                val pagerState = rememberPagerState(pageCount = { ordered.size })
                Box {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    ) { page ->
                        AsyncImage(
                            model = repository.fileFor(ordered[page]),
                            contentDescription = "Photo ${page + 1} of ${ordered.size}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(FanLensColors.PaperRaised)
                        )
                    }
                    if (ordered.size > 1) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ordered.indices.forEach { index ->
                                Box(
                                    Modifier
                                        .size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (index == pagerState.currentPage) FanLensColors.BrandRed
                                            else FanLensColors.TrackingBase
                                        )
                                )
                            }
                        }
                    }
                }
            }

            Column(Modifier.padding(24.dp)) {
                Text(
                    text = details.product.brand,
                    style = MaterialTheme.typography.labelMedium,
                    color = FanLensColors.InkMuted,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = details.product.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = FanLensColors.Ink
                )
                if (details.product.model.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = details.product.model,
                        color = FanLensColors.BrandRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                details.product.priceLabel?.let { price ->
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(price, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        details.product.mrpLabel?.let { mrp ->
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = mrp,
                                style = MaterialTheme.typography.bodyMedium,
                                color = FanLensColors.InkMuted,
                                textDecoration = TextDecoration.LineThrough
                            )
                        }
                        details.product.discountPercent?.let { percent ->
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "$percent% off",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = FanLensColors.BrandRed
                            )
                        }
                    }
                }

                if (details.product.description.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(details.product.description, color = FanLensColors.InkMuted)
                }

                val facts = buildList {
                    details.product.category?.let { add("Category" to it) }
                    details.product.colour?.let { add("Colour" to it) }
                    details.product.sizeSweepMm?.let { add("Size" to "$it mm") }
                    addAll(details.product.specs.map { it.key to it.value })
                    add("Shop photos" to "${details.recognitionPhotos.size}")
                    if (details.displayPhotos.isNotEmpty()) {
                        add("Catalogue photos" to "${details.displayPhotos.size}")
                    }
                }
                if (facts.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = FanLensColors.Rule)
                    facts.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(
                                text = label,
                                modifier = Modifier.fillMaxWidth(.4f),
                                color = FanLensColors.InkMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(value, color = FanLensColors.Ink, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider(color = FanLensColors.Rule)
                    }
                }

                Spacer(Modifier.height(28.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onDelete(productId) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    color = FanLensColors.PaperRaised
                ) {
                    Text(
                        text = "Delete this product",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
