/*
 * This file is part of Fim - File Integrity Manager
 *
 * Copyright (C) 2015  Etienne Vrignaud
 *
 * Fim is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Fim is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Fim.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.fim;

import org.fim.model.CompareMode;
import org.fim.model.CompareResult;
import org.fim.tool.BuildableState;
import org.fim.tool.Modification;
import org.fim.tool.StateAssert;
import org.junit.Test;

public class StateComparatorFastTest extends StateAssert
{
	private StateComparator cut = new StateComparator(CompareMode.FAST);
	private BuildableState s1 = new BuildableState().addFiles("file_01", "file_02", "file_03", "file_04");
	private BuildableState s2;

	@Test
	public void weCanDoCompareOnSimpleOperations()
	{
		// Set the same file content
		s2 = s1.setContent("file_01", "file_01");
		CompareResult result = cut.compare(s1, s2);
		assertNothingModified(result);

		s2 = s1.addFiles("file_05");
		result = cut.compare(s1, s2);
		assertOnlyFilesAdded(result, "file_05");

		s2 = s1.touch("file_01");
		result = cut.compare(s1, s2);
		assertOnlyDatesModified(result, "file_01");

		s2 = s1.appendContent("file_01", "append 1");
		result = cut.compare(s1, s2);
		assertOnlyContentModified(result, "file_01");

		s2 = s1.rename("file_01", "file_06");
		result = cut.compare(s1, s2);
		assertGotOnlyModifications(result, Modification.ADDED, Modification.DELETED);
		assertFilesModified(result, Modification.DELETED, "file_01");
		assertFilesModified(result, Modification.ADDED, "file_06");

		s2 = s1.copy("file_01", "file_06");
		result = cut.compare(s1, s2);
		assertOnlyFilesAdded(result, "file_06");

		s2 = s1.delete("file_01");
		result = cut.compare(s1, s2);
		assertOnlyFileDeleted(result, "file_01");
	}

	@Test
	public void weCanCopyAFileAndChangeDate()
	{
		s2 = s1.copy("file_01", "file_00")
				.copy("file_01", "file_06")
				.touch("file_01");
		CompareResult result = cut.compare(s1, s2);
		assertGotOnlyModifications(result, Modification.ADDED, Modification.DATE_MODIFIED);
		assertFilesModified(result, Modification.ADDED, "file_00", "file_06");
		assertFilesModified(result, Modification.DATE_MODIFIED, "file_01");
	}

	@Test
	public void weCanCopyAFileAndChangeContent()
	{
		s2 = s1.copy("file_01", "file_00")
				.copy("file_01", "file_06")
				.appendContent("file_01", "append 1");
		CompareResult result = cut.compare(s1, s2);
		assertGotOnlyModifications(result, Modification.ADDED, Modification.CONTENT_MODIFIED);
		assertFilesModified(result, Modification.ADDED, "file_00", "file_06");
		assertFilesModified(result, Modification.CONTENT_MODIFIED, "file_01");
	}
}
