package org.cobalt

import net.fabricmc.api.ClientModInitializer
import org.cobalt.api.command.CommandManager
import org.cobalt.api.event.EventBus
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.hud.modules.WatermarkModule
import org.cobalt.api.hud.modules.InventoryHudModule
import org.cobalt.api.module.ModuleManager
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.util.TickScheduler
import org.cobalt.internal.combat.CombatMacroModule
import org.cobalt.internal.command.MainCommand
import org.cobalt.internal.etherwarp.EtherwarpHelperModule
import org.cobalt.internal.etherwarp.LeftClickEtherwarpModule
import org.cobalt.internal.helper.Config
import org.cobalt.internal.loader.AddonLoader
import org.cobalt.internal.mining.MiningModule
import org.cobalt.internal.mining.FairyModule
import org.cobalt.internal.mining.RoutesModule
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.qol.QolModule
import org.cobalt.internal.visual.BlockOutlineModule

@Suppress("UNUSED")
object Cobalt : ClientModInitializer {


  override fun onInitializeClient() {
    ModuleManager.addModules(
      listOf(
        WatermarkModule(),
        InventoryHudModule(),
        MiningModule,
        FairyModule,
        RoutesModule,
        CombatMacroModule,
        EtherwarpHelperModule,
        LeftClickEtherwarpModule,
        PathfindingModule,
        BlockOutlineModule,
        QolModule
      )
    )

    AddonLoader.getAddons().map { it.second }.forEach {
      it.onLoad()
      ModuleManager.addModules(it.getModules())
    }

    CommandManager.register(MainCommand)
    CommandManager.dispatchAll()

    listOf(
      TickScheduler, MainCommand, NotificationManager,
      RotationExecutor, HudModuleManager,
    ).forEach { EventBus.register(it) }
    Config.loadModulesConfig()
    EventBus.register(this)
    println("Dutt Client Initialized")
  }

}
