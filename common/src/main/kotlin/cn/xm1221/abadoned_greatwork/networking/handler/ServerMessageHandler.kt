package cn.xm1221.abadoned_greatwork.networking.handler

import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import cn.xm1221.abadoned_greatwork.networking.msg.Abadoned_greatworkMessageC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderLoadC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderLoadS2C
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderSandboxExecC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderSandboxExecS2C
import cn.xm1221.abadoned_greatwork.ponder.data.PonderSceneLoader
import cn.xm1221.abadoned_greatwork.ponder.sim.PonderPreSimulator
import dev.architectury.networking.NetworkManager.PacketContext
import net.minecraft.server.level.ServerPlayer

fun Abadoned_greatworkMessageC2S.applyOnServer(ctx: PacketContext) = ctx.queue {
    when (this) {
        is MsgPonderLoadC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            val scene = PonderSceneLoader.getScene(sceneId) ?: return@queue
            val bundle = PonderPreSimulator.simulate(scene, player)
            MsgPonderLoadS2C.sendChunked(player, bundle)
        }

        is MsgPonderSandboxExecC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            val pattern = try {
                HexPattern.fromAngles(sig, HexDir.valueOf(startDir.uppercase()))
            } catch (_: Exception) {
                return@queue
            }
            val result = PonderPreSimulator.executeSandbox(player, pattern, imageNbt)
            MsgPonderSandboxExecS2C(
                sessionId = sessionId,
                imageNbt = result.imageNbt,
                resolutionType = result.resolutionType,
                consoleLines = result.consoleLines,
                mishapText = result.mishapText,
            ).sendToPlayer(player)
        }
    }
}
