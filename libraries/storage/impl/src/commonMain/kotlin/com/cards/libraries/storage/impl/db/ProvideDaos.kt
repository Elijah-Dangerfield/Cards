package com.dangerfield.cards.libraries.storage.impl.db

import com.dangerfield.cards.libraries.cards.storage.db.AchievementDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ClearableDao
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentDao
import com.dangerfield.cards.libraries.cards.storage.db.InventoryDao
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionDao
import com.dangerfield.cards.libraries.cards.storage.db.SessionDao
import com.dangerfield.cards.libraries.cards.storage.db.UserDao
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = UserDao::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = ClearableDao::class)
class ProvideUserDao @Inject constructor(
    provider: AppDatabaseProvider
) : UserDao by provider.database.userDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = SessionDao::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = ClearableDao::class)
class ProvideSessionDao @Inject constructor(
    provider: AppDatabaseProvider
) : SessionDao by provider.database.sessionDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ProgressionDao::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = ClearableDao::class)
class ProvideProgressionDao @Inject constructor(
    provider: AppDatabaseProvider
) : ProgressionDao by provider.database.progressionDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = XpEventDao::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = ClearableDao::class)
class ProvideXpEventDao @Inject constructor(
    provider: AppDatabaseProvider
) : XpEventDao by provider.database.xpEventDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ChipsDao::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = ClearableDao::class)
class ProvideChipsDao @Inject constructor(
    provider: AppDatabaseProvider
) : ChipsDao by provider.database.chipsDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AchievementDao::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = ClearableDao::class)
class ProvideAchievementDao @Inject constructor(
    provider: AppDatabaseProvider
) : AchievementDao by provider.database.achievementDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = InventoryDao::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = ClearableDao::class)
class ProvideInventoryDao @Inject constructor(
    provider: AppDatabaseProvider
) : InventoryDao by provider.database.inventoryDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = EquipmentDao::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = ClearableDao::class)
class ProvideEquipmentDao @Inject constructor(
    provider: AppDatabaseProvider
) : EquipmentDao by provider.database.equipmentDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = WalletEventDao::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = ClearableDao::class)
class ProvideWalletEventDao @Inject constructor(
    provider: AppDatabaseProvider
) : WalletEventDao by provider.database.walletEventDao()
