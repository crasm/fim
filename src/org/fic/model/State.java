package org.fic.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Created by evrignaud on 05/05/15.
 */
public class State
{
	private long timestamp = System.currentTimeMillis();
	private String message = "";
	private int fileCount = 0;
	private List<FileState> fileStates = null;

	public void loadFromZipFile(File stateFile) throws IOException
	{
		try (Reader reader = new InputStreamReader(new GZIPInputStream(new FileInputStream(stateFile))))
		{
			Gson gson = new Gson();
			State state = gson.fromJson(reader, State.class);
			timestamp = state.timestamp;
			message = state.message;
			fileCount = state.fileStates.size();
			fileStates = state.fileStates;
		}
	}

	public void saveToZipFile(File stateFile) throws IOException
	{
		fileCount = fileStates.size();

		try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(stateFile))))
		{
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			gson.toJson(this, writer);
		}
	}

	public long getTimestamp()
	{
		return timestamp;
	}

	public String getMessage()
	{
		return message;
	}

	public void setMessage(String message)
	{
		this.message = message;
	}

	public List<FileState> getFileStates()
	{
		return fileStates;
	}

	public int getFileCount()
	{
		return fileCount;
	}

	public void setFileStates(List<FileState> fileStates)
	{
		this.fileStates = fileStates;
	}
}
