package depo_mountain.depo_mountain_1_16_5;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.extension.platform.AbstractPlayerActor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * コマンド処理クラス
 * WorldEditがない環境でWorldEditのクラスを読み込まないよう、別クラスに移動
 */
public class CommandListener implements CommandExecutor, TabCompleter {
    // コマンドを実際に処理
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        // プレイヤーがコマンドを投入した際の処理...
        if (cmd.getName().equalsIgnoreCase("/DpMountain")) {
            // プレイヤーチェック
            if (!(sender instanceof Player)) {
                // コマブロやコンソールからの実行の場合
                sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーのみが使えます。");
                return true;
            }
            Player player = (Player) sender;

            // WorldEditを取得
            WorldEditPlugin worldEdit = JavaPlugin.getPlugin(WorldEditPlugin.class);
            AbstractPlayerActor wPlayer = worldEdit.wrapPlayer(player);
            World wWorld = wPlayer.getWorld();

            // プレイヤーセッション
            LocalSession session = WorldEdit.getInstance()
                    .getSessionManager()
                    .get(wPlayer);

            if (!session.isSelectionDefined(wWorld)) {
                // 範囲が選択されていない場合
                sender.sendMessage(ChatColor.RED + "WorldEditの範囲が選択されていません。");
                return true;
            }

            Region region;
            try {
                region = session.getSelection(wWorld);
            } catch (WorldEditException e) {
                // 範囲が不完全です
                sender.sendMessage(ChatColor.RED + "WorldEditの範囲が不完全です。");
                return true;
            }

            //コマンド引数を処理
            CommandParser parser = CommandParser.parseCommand(sender, args);
            if (!parser.isSuccess) {
                // パース失敗
                return true;
            }
            BlockVector3 minimum_pos = region.getMinimumPoint();
            BlockVector3 maximum_pos = region.getMaximumPoint();
            if(parser.b_Y_Limited) {
                minimum_pos = BlockVector3.at(minimum_pos.getBlockX() + 1, minimum_pos.getBlockY(), minimum_pos.getBlockZ() + 1);
                maximum_pos = BlockVector3.at(maximum_pos.getBlockX() - 1, maximum_pos.getBlockY(), maximum_pos.getBlockZ() - 1);
            }else {
                minimum_pos = BlockVector3.at(minimum_pos.getBlockX(), 0, minimum_pos.getBlockZ());
                maximum_pos = BlockVector3.at(maximum_pos.getBlockX(), 255, maximum_pos.getBlockZ());
            }
            // 範囲を設定
            CuboidRegion bound = new CuboidRegion(region.getWorld(), minimum_pos, maximum_pos);
            //bound.expand(
            //        new Vector(0, (bound.getWorld().getMaxY() + 1), 0),
            //        new Vector(0, -(bound.getWorld().getMaxY() + 1), 0));

            // 範囲中のラピスラズリブロックの位置を座標指定型で記録
            int[][] heightmapArray = new int[bound.getWidth()][bound.getLength()];
            // 範囲中のラピスラズリブロックの位置をリストとして記録
            ArrayList<ControlPointData> heightControlPoints = new ArrayList<>();

            // 複数ティックに分けて操作をするための準備
            OperationExecutor executor = new OperationExecutor();
            executor.start();

            MountOperation mountOperation = new MountOperation(bound);
            // ラピスラズリブロックを目印として、範囲中のデータを取得
            executor.run(
                    mountOperation.collectSurfacePoints(wWorld, bound, parser.bCollectBorder, heightmapArray, heightControlPoints),
                    s -> s.forEach(str -> sender.sendMessage(ChatColor.GREEN + "ラピスラズリの位置を取得中... " + str)),
                    e -> sender.sendMessage(ChatColor.RED + "ラピスブロック位置の取得中にエラーが発生しました。"),
                    () -> {
                        // 距離が近い順にk個取り出す。ただし、numInterpolationPointsArg=0の時は全部
                        int size = heightControlPoints.size();
                        int maxi;
                        if (parser.numInterpolationPoints == 0) {
                            if (size == 0) {
                                sender.sendMessage(ChatColor.RED + "最低一つはラピスラズリブロックをおいてください。");
                                return false;
                            }
                            maxi = size;
                        } else {
                            if (size < parser.numInterpolationPoints) {
                                sender.sendMessage(ChatColor.RED + "kより多いラピスラズリブロックをおいてください。");
                                return false;
                            }
                            maxi = parser.numInterpolationPoints;
                        }

                        // 地形の補間計算
                        executor.run(
                                mountOperation.interpolateSurface(maxi, bound, heightmapArray, heightControlPoints,parser.b_degree),
                                s -> s.forEach(str -> sender.sendMessage(ChatColor.GREEN + "地形補間を計算中... " + str)),
                                e -> sender.sendMessage(ChatColor.RED + "地形補間の計算中にエラーが発生しました。"),
                                () -> {
                                    // ブロック変更開始 (WorldEditのUndoに登録される)
                                    EditSession editSession = worldEdit.createEditSession(player);
                                    // 範囲中の地形を実際に改変
                                    executor.run(
                                            mountOperation.applySurface(editSession, wWorld, parser.bReplaceAll, bound, heightmapArray),
                                            s -> s.forEach(str -> sender.sendMessage(ChatColor.GREEN + "ブロックを設置中... " + str)),
                                            e -> sender.sendMessage(ChatColor.RED + e.getMessage()),
                                            () -> {
                                                sender.sendMessage(ChatColor.GREEN + "ブロックを反映中...");
                                                editSession.close();
                                                session.remember(editSession);
                                                sender.sendMessage(ChatColor.GREEN + "設置完了");
                                                return false;
                                            }
                                    );

                                    return true;
                                }
                        );

                        return true;
                    }
            );

            return true;
        }

        // コマンドが実行されなかった場合は、falseを返して当メソッドを抜ける。
        return false;

        // done undo がシングルのみ対応、done あと、向きが分かりにくい.done k実装、done 空気のみに作用させるか done ラピスラズリブロックなかったとき done境界条件
    }

    // コマンドのTAB補完の実装
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return CommandParser.suggestCommand(sender, args);
    }
}
