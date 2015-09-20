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
package org.fim.internal;

import static org.fim.model.HashMode.dontHash;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.fim.model.Context;
import org.fim.model.FileState;
import org.fim.model.HashMode;
import org.fim.model.State;
import org.fim.util.Console;
import org.fim.util.FileUtil;
import org.fim.util.Logger;

public class StateGenerator
{
	public static final String DOT_FIM_IGNORE = ".fimignore";

	public static final int PROGRESS_DISPLAY_FILE_COUNT = 10;
	public static final int FILES_QUEUE_CAPACITY = 500;

	private static final Set ignoredDirectories = new HashSet<>(Arrays.asList(Context.DOT_FIM_DIR, ".git", ".svn", ".cvs"));

	private static final List<Pair<Character, Integer>> hashProgress = Arrays.asList(
			Pair.of('.', 0),
			Pair.of('o', FileState.SIZE_20_MB),
			Pair.of('O', FileState.SIZE_50_MB),
			Pair.of('@', FileState.SIZE_100_MB),
			Pair.of('#', FileState.SIZE_200_MB)
	);

	private static Comparator<FileState> fileNameComparator = new FileState.FileNameComparator();

	private final Context context;
	private final ReentrantLock progressLock;

	private ExecutorService executorService;
	private long summedFileLength;
	private int fileCount;
	private long totalFileContentLength;

	private Path rootDir;
	private BlockingDeque<Path> filesToHashQueue;
	private boolean hashersStarted;
	private List<FileHasher> hashers;
	private long totalBytesHashed;
	private String repositoryRootDirString;
	private List<String> ignoredFiles;

	public StateGenerator(Context context)
	{
		this.context = context;
		this.progressLock = new ReentrantLock();
		this.repositoryRootDirString = context.getRepositoryRootDir().toString();
	}

	public static String hashModeToString(HashMode hashMode)
	{
		switch (hashMode)
		{
			case dontHash:
				return "retrieve only file attributes";

			case hashSmallBlock:
				return "hash second 4 KB block";

			case hashMediumBlock:
				return "hash second 1 MB block";

			case hashAll:
				return "hash the complete file";
		}

		throw new IllegalArgumentException("Invalid hash mode " + hashMode);
	}

	public State generateState(String comment, Path rootDir, Path dirToScan) throws NoSuchAlgorithmException
	{
		this.rootDir = rootDir;

		ignoredFiles = new ArrayList<>();

		Logger.info(String.format("Scanning recursively local files, %s, using %d thread", hashModeToString(context.getHashMode()), context.getThreadCount()));
		if (displayHashLegend())
		{
			System.out.printf("(Hash progress legend for files grouped %d by %d: %s)%n", PROGRESS_DISPLAY_FILE_COUNT, PROGRESS_DISPLAY_FILE_COUNT, hashProgressLegend());
		}

		State state = new State();
		state.setComment(comment);
		state.setHashMode(context.getHashMode());

		long start = System.currentTimeMillis();
		progressOutputInit();

		filesToHashQueue = new LinkedBlockingDeque<>(FILES_QUEUE_CAPACITY);
		InitializeFileHashers();

		scanFileTree(filesToHashQueue, dirToScan);

		// In case the FileHashers have not already been started
		startFileHashers();

		waitAllFilesToBeHashed();

		for (FileHasher hasher : hashers)
		{
			state.getFileStates().addAll(hasher.getFileStates());
			totalFileContentLength += hasher.getTotalFileContentLength();
			totalBytesHashed += hasher.getTotalBytesHashed();
		}

		Collections.sort(state.getFileStates(), fileNameComparator);

		state.setIgnoredFiles(ignoredFiles);

		progressOutputStop();
		displayStatistics(start, state);

		return state;
	}

	private void InitializeFileHashers()
	{
		hashersStarted = false;
		hashers = new ArrayList<>();
		executorService = Executors.newFixedThreadPool(context.getThreadCount());
	}

	private void startFileHashers() throws NoSuchAlgorithmException
	{
		if (!hashersStarted)
		{
			String normalizedRootDir = FileUtil.getNormalizedFileName(rootDir);
			for (int index = 0; index < context.getThreadCount(); index++)
			{
				FileHasher hasher = new FileHasher(this, filesToHashQueue, normalizedRootDir);
				executorService.submit(hasher);
				hashers.add(hasher);
			}
			hashersStarted = true;
		}
	}

	private void waitAllFilesToBeHashed()
	{
		try
		{
			executorService.shutdown();
			executorService.awaitTermination(3, TimeUnit.DAYS);
		}
		catch (InterruptedException ex)
		{
			Logger.error(ex);
		}
	}

	private void displayStatistics(long start, State state)
	{
		long duration = System.currentTimeMillis() - start;

		String totalFileContentLengthStr = FileUtils.byteCountToDisplaySize(totalFileContentLength);
		String totalBytesHashedStr = FileUtils.byteCountToDisplaySize(totalBytesHashed);
		String durationStr = DurationFormatUtils.formatDuration(duration, "HH:mm:ss");

		long durationSeconds = duration / 1000;
		if (durationSeconds <= 0)
		{
			durationSeconds = 1;
		}

		long globalThroughput = totalBytesHashed / durationSeconds;
		String throughputStr = FileUtils.byteCountToDisplaySize(globalThroughput);

		if (context.getHashMode() == dontHash)
		{
			Logger.info(String.format("Scanned %d files (%s), during %s, using %d thread%n",
					state.getFileStates().size(), totalFileContentLengthStr, durationStr, context.getThreadCount()));
		}
		else
		{
			Logger.info(String.format("Scanned %d files (%s), hashed %s (avg %s/s), during %s, using %d thread%n",
					state.getFileStates().size(), totalFileContentLengthStr, totalBytesHashedStr, throughputStr, durationStr, context.getThreadCount()));
		}
	}

	private void scanFileTree(BlockingDeque<Path> filesToHashQueue, Path directory) throws NoSuchAlgorithmException
	{
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory))
		{
			List<FileToIgnore> localIgnore = loadLocalIgnore(directory);

			for (Path file : stream)
			{
				if (!hashersStarted && filesToHashQueue.size() > FILES_QUEUE_CAPACITY / 2)
				{
					startFileHashers();
				}

				BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				if (isIgnored(file, attributes, localIgnore))
				{
					addToIgnoredFiles(file, attributes);
				}
				else
				{
					if (attributes.isRegularFile())
					{
						enqueueFile(filesToHashQueue, file);
					}
					else if (attributes.isDirectory())
					{
						scanFileTree(filesToHashQueue, file);
					}
				}
			}
		}
		catch (IOException ex)
		{
			Console.newLine();
			Logger.error("Skipping - Error scanning directory", ex);
		}
	}

	private void addToIgnoredFiles(Path file, BasicFileAttributes attributes)
	{
		String normalizedFileName = FileUtil.getNormalizedFileName(file);
		if (attributes.isDirectory())
		{
			normalizedFileName = normalizedFileName + "/";
		}

		String relativeFileName = FileUtil.getRelativeFileName(repositoryRootDirString, normalizedFileName);
		ignoredFiles.add(relativeFileName);
	}

	protected List<FileToIgnore> loadLocalIgnore(Path directory)
	{
		List<FileToIgnore> localIgnore = new ArrayList<>();

		Path dotFimIgnore = directory.resolve(DOT_FIM_IGNORE);
		if (Files.exists(dotFimIgnore))
		{
			List<String> allLines = null;
			try
			{
				allLines = Files.readAllLines(dotFimIgnore);
			}
			catch (IOException e)
			{
				Logger.error(String.format("Unable to read file %s: %s", dotFimIgnore, e.getMessage()));
				return localIgnore;
			}

			for (String line : allLines)
			{
				FileToIgnore fileToIgnore = new FileToIgnore(line);
				localIgnore.add(fileToIgnore);
			}
		}

		return localIgnore;
	}

	protected boolean isIgnored(Path file, BasicFileAttributes attributes, List<FileToIgnore> localIgnore)
	{
		String fileName = file.getFileName().toString();
		if (attributes.isDirectory() && ignoredDirectories.contains(fileName))
		{
			return true;
		}

		for (FileToIgnore fileToIgnore : localIgnore)
		{
			if (fileToIgnore.getCompiledFilename() != null)
			{
				Matcher matcher = fileToIgnore.getCompiledFilename().matcher(fileName);
				if (matcher.find())
				{
					return true;
				}
			}
			else if (fileToIgnore.getRegexpFileName().equals(fileName))
			{
				return true;
			}
		}

		return false;
	}

	private void enqueueFile(BlockingDeque<Path> filesToHashQueue, Path file)
	{
		try
		{
			filesToHashQueue.offer(file, 120, TimeUnit.MINUTES);
		}
		catch (InterruptedException ex)
		{
			Logger.error(ex);
		}
	}

	private void progressOutputInit()
	{
		summedFileLength = 0;
		fileCount = 0;
	}

	public void updateProgressOutput(long fileSize) throws IOException
	{
		progressLock.lock();
		try
		{
			fileCount++;

			if (displayHashLegend())
			{
				summedFileLength += fileSize;

				if (fileCount % PROGRESS_DISPLAY_FILE_COUNT == 0)
				{
					System.out.print(getProgressChar(summedFileLength));
					summedFileLength = 0;
				}
			}

			if (fileCount % (100 * PROGRESS_DISPLAY_FILE_COUNT) == 0)
			{
				if (displayHashLegend())
				{
					Console.newLine();
				}
			}
		}
		finally
		{
			progressLock.unlock();
		}
	}

	private String hashProgressLegend()
	{
		StringBuilder sb = new StringBuilder();
		for (int progressIndex = hashProgress.size() - 1; progressIndex >= 0; progressIndex--)
		{
			Pair<Character, Integer> progressPair = hashProgress.get(progressIndex);
			char marker = progressPair.getLeft();
			sb.append(marker);

			int fileLength = progressPair.getRight();
			if (fileLength == 0)
			{
				sb.append(" otherwise");
			}
			else
			{
				sb.append(" > ").append(FileUtils.byteCountToDisplaySize(fileLength));
			}
			sb.append(", ");
		}
		String legend = sb.toString();
		legend = legend.substring(0, legend.length() - 2);
		return legend;
	}

	protected char getProgressChar(long fileLength)
	{
		int progressIndex;
		for (progressIndex = hashProgress.size() - 1; progressIndex >= 0; progressIndex--)
		{
			Pair<Character, Integer> progressPair = hashProgress.get(progressIndex);
			if (fileLength >= progressPair.getRight())
			{
				return progressPair.getLeft();
			}
		}

		return ' ';
	}

	private void progressOutputStop()
	{
		if (displayHashLegend())
		{
			if (fileCount >= PROGRESS_DISPLAY_FILE_COUNT)
			{
				Console.newLine();
			}
		}
	}

	private boolean displayHashLegend()
	{
		return context.isVerbose() && context.getHashMode() != dontHash;
	}

	public Context getContext()
	{
		return context;
	}
}
