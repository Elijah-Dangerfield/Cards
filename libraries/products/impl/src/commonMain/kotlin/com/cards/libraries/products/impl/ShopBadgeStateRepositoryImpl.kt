package com.dangerfield.cards.libraries.products.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.products.ShopBadgeStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ShopBadgeStateRepository::class)
@Inject
class ShopBadgeStateRepositoryImpl(
    private val productsRepository: ProductsRepository,
    private val appCache: AppCache,
) : ShopBadgeStateRepository {

    override fun observeHasUnseenItems(): Flow<Boolean> = combine(
        productsRepository.observeCatalog(),
        appCache.updates,
    ) { catalog, app ->
        val catalogIds = catalog.allProductIds()
        catalogIds.any { it !in app.shopSeenProductIds }
    }.distinctUntilChanged()

    override suspend fun markCurrentItemsSeen() {
        val catalogIds = productsRepository.observeCatalog().first().allProductIds()
        if (catalogIds.isEmpty()) return
        appCache.update { it.copy(shopSeenProductIds = catalogIds) }
    }

    private fun ProductCatalog.allProductIds(): Set<String> {
        val ids = mutableSetOf<String>()
        chipPacks.forEach { ids.add(it.id) }
        chipOffers.forEach { ids.add(it.id) }
        return ids
    }
}
