/*
 * Copyright (C) 2014-2015  Gnat008
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.gnat008.perworldinventory.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

public class PerWorldInventoryCommand implements ExecutableCommand {

    @Override
    public void executeCommand(CommandSender sender, List<String> args) {
        sender.sendMessage(ChatColor.BLUE + "» " + ChatColor.GRAY + "Use the command " + ChatColor.WHITE + "/pwi help"
                + ChatColor.GRAY + " for help.");
        sender.sendMessage(ChatColor.BLUE + "» " + ChatColor.GRAY + "Use the command " + ChatColor.WHITE + "/pwi version"
                + ChatColor.GRAY + " for version info.");
    }
}
