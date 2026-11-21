package cn.xm1221.abadoned_greatwork.ponder

import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.item.IotaHolderItem
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderLoadS2C
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderOpenIndexS2C
import cn.xm1221.abadoned_greatwork.ponder.data.PonderIotaCodec
import cn.xm1221.abadoned_greatwork.ponder.data.PonderSceneLoader
import cn.xm1221.abadoned_greatwork.ponder.data.PonderUnlockManager
import cn.xm1221.abadoned_greatwork.ponder.sim.PonderPreSimulator
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.architectury.platform.Platform
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component
import java.nio.file.Files

/**
 * 问天系统服务端指令（`/abw tianwen ...`，保留 `/abw ponder ...` 别名）：
 * - `/abw tianwen index`：打开问天目录与《咒术笔记》搜索界面
 * - `/abw tianwen open <scene>`：直接打开指定引导章节
 * - `/abw tianwen export <name>`：将副手/主手持有的 IotaHolderItem 导出为数据驱动章节草稿
 */
object PonderCommands {

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("abw")
                .then(buildSubtree("tianwen"))
                .then(buildSubtree("ponder"))
        )
    }

    private fun buildSubtree(rootName: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(rootName)
            .then(
                Commands.literal("index").executes { ctx ->
                    val player = ctx.source.playerOrException
                    PonderSceneLoader.syncIndexTo(player)
                    MsgPonderOpenIndexS2C().sendToPlayer(player)
                    1
                }
            )
            .then(
                Commands.literal("open").then(
                    Commands.argument("scene", ResourceLocationArgument.id())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggestResource(PonderSceneLoader.scenes.keys, builder)
                        }
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val id = ResourceLocationArgument.getId(ctx, "scene")
                            val scene = PonderSceneLoader.getScene(id)
                            if (scene == null) {
                                ctx.source.sendFailure(Component.translatable("command.abadoned_greatwork.ponder.not_found", id.toString()))
                                return@executes 0
                            }
                            if (!player.hasPermissions(2) && !PonderUnlockManager.isUnlocked(player, scene)) {
                                ctx.source.sendFailure(Component.translatable("hexponder.ui.locked_deny"))
                                return@executes 0
                            }
                            PonderUnlockManager.markViewed(player, scene.id)
                            val bundle = PonderPreSimulator.simulate(scene, player)
                            MsgPonderLoadS2C.sendChunked(player, bundle)
                            1
                        }
                )
            )
            .then(
                Commands.literal("unlock").requires { it.hasPermission(2) }.then(
                    Commands.argument("scene", ResourceLocationArgument.id())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggestResource(PonderSceneLoader.scenes.keys, builder)
                        }
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val id = ResourceLocationArgument.getId(ctx, "scene")
                            if (PonderSceneLoader.getScene(id) == null) {
                                ctx.source.sendFailure(Component.translatable("command.abadoned_greatwork.ponder.not_found", id.toString()))
                                return@executes 0
                            }
                            PonderUnlockManager.unlockScene(player, id)
                            ctx.source.sendSuccess({ Component.translatable("command.abadoned_greatwork.ponder.unlocked", id.toString()) }, false)
                            1
                        }
                )
            )
            .then(
                Commands.literal("unlock_all").requires { it.hasPermission(2) }.executes { ctx ->
                    val player = ctx.source.playerOrException
                    PonderUnlockManager.unlockAll(player)
                    ctx.source.sendSuccess({ Component.translatable("command.abadoned_greatwork.ponder.unlocked_all") }, false)
                    1
                }
            )
            .then(
                Commands.literal("lock").requires { it.hasPermission(2) }.then(
                    Commands.argument("scene", ResourceLocationArgument.id())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggestResource(PonderSceneLoader.scenes.keys, builder)
                        }
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val id = ResourceLocationArgument.getId(ctx, "scene")
                            if (PonderSceneLoader.getScene(id) == null) {
                                ctx.source.sendFailure(Component.translatable("command.abadoned_greatwork.ponder.not_found", id.toString()))
                                return@executes 0
                            }
                            PonderUnlockManager.lockScene(player, id)
                            ctx.source.sendSuccess({ Component.translatable("command.abadoned_greatwork.ponder.locked", id.toString()) }, false)
                            1
                        }
                )
            )
            .then(
                Commands.literal("reset_locks").requires { it.hasPermission(2) }.executes { ctx ->
                    val player = ctx.source.playerOrException
                    PonderUnlockManager.resetPlayer(player)
                    ctx.source.sendSuccess({ Component.translatable("command.abadoned_greatwork.ponder.reset_locks") }, false)
                    1
                }
            )
            .then(
                Commands.literal("export").requires { it.hasPermission(2) }.then(
                    Commands.argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val name = StringArgumentType.getString(ctx, "name")
                            val stack = listOf(player.mainHandItem, player.offhandItem)
                                .firstOrNull { it.item is IotaHolderItem }
                            if (stack == null) {
                                ctx.source.sendFailure(Component.translatable("command.abadoned_greatwork.ponder.export_no_item"))
                                return@executes 0
                            }
                            val holder = stack.item as IotaHolderItem
                            val iota = holder.readIota(stack, player.serverLevel())
                            if (iota == null) {
                                ctx.source.sendFailure(Component.translatable("command.abadoned_greatwork.ponder.export_empty"))
                                return@executes 0
                            }
                            val list = if (iota is ListIota) iota.list.toList() else listOf(iota)
                            val json = buildDraftSceneJson(name, list)
                            val outDir = Platform.getGameFolder()
                                .resolve(Abadoned_greatwork.MODID)
                                .resolve("hex_ponder")
                                .resolve("scenes")
                            Files.createDirectories(outDir)
                            val outPath = outDir.resolve("$name.json")
                            Files.writeString(outPath, json, Charsets.UTF_8)
                            ctx.source.sendSuccess(
                                { Component.translatable("command.abadoned_greatwork.ponder.exported", outPath.toString()) },
                                false,
                            )
                            1
                        }
                )
            )

    private fun buildDraftSceneJson(name: String, iotas: List<at.petrak.hexcasting.api.casting.iota.Iota>): String {
        val root = JsonObject()
        root.addProperty("id", "${Abadoned_greatwork.MODID}:$name")
        root.addProperty("title", name)
        root.addProperty("category", "basics")
        root.addProperty("cursor_item", PonderSceneLoader.DEFAULT_CURSOR_ITEM)
        root.addProperty("interval", 36)
        root.addProperty("stroke_ticks", 14)
        root.addProperty("clear_before", true)

        val steps = JsonArray()
        for (iota in iotas) {
            val step = JsonObject()
            if (iota is PatternIota) {
                val pat = iota.pattern
                step.addProperty("type", "execute")
                val actionId = HexActions.REGISTRY.entrySet()
                    .firstOrNull { pat.sigsEqual(it.value.prototype()) }
                    ?.key?.location()
                if (actionId != null) {
                    step.addProperty("action", actionId.toString())
                } else {
                    step.addProperty("pattern", pat.anglesSignature())
                    step.addProperty("start_dir", pat.startDir.name)
                }
            } else {
                step.addProperty("type", "push")
                step.addProperty("push", "iota:${PonderIotaCodec.encodeToA85(iota)}")
            }
            steps.add(step)
        }
        root.add("steps", steps)
        return GSON.toJson(root)
    }
}
