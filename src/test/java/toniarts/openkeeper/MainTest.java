/*
 * Copyright (C) 2014-2026 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @AfterEach
    void clearParameters() {
        Main.parseArguments(new String[0]);
    }

    @Test
    void levelParameterExitsOnlyAfterAWin() {
        Main.parseArguments(new String[] {"-level", "Maps/Level1.kwd"});

        assertTrue(Main.shouldExitAfterLevelWin(true));
        assertFalse(Main.shouldExitAfterLevelWin(false));
    }

    @Test
    void regularLaunchKeepsTheNormalEndGameFlow() {
        Main.parseArguments(new String[] {"-nomovies"});

        assertFalse(Main.shouldExitAfterLevelWin(true));
    }
}
