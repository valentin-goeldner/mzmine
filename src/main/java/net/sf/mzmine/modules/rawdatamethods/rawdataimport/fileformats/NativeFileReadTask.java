/*
 * Copyright 2006-2015 The MZmine 2 Development Team
 * 
 * This file is part of MZmine 2.
 * 
 * MZmine 2 is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * MZmine 2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * MZmine 2; if not, write to the Free Software Foundation, Inc., 51 Franklin
 * St, Fifth Floor, Boston, MA 02110-1301 USA
 */

package net.sf.mzmine.modules.rawdatamethods.rawdataimport.fileformats;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.mzmine.datamodel.DataPoint;
import net.sf.mzmine.datamodel.MZmineProject;
import net.sf.mzmine.datamodel.MassSpectrumType;
import net.sf.mzmine.datamodel.Polarity;
import net.sf.mzmine.datamodel.RawDataFile;
import net.sf.mzmine.datamodel.RawDataFileWriter;
import net.sf.mzmine.datamodel.impl.SimpleDataPoint;
import net.sf.mzmine.datamodel.impl.SimpleScan;
import net.sf.mzmine.modules.rawdatamethods.rawdataimport.RawDataFileType;
import net.sf.mzmine.taskcontrol.AbstractTask;
import net.sf.mzmine.taskcontrol.TaskStatus;
import net.sf.mzmine.util.ExceptionUtils;
import net.sf.mzmine.util.ScanUtils;
import net.sf.mzmine.util.TextUtils;

import com.google.common.collect.Range;

/**
 * This module binds spawns a separate process that dumps the native format's
 * data in a text+binary form into its standard output. This class then reads
 * the output of that process.
 */
public class NativeFileReadTask extends AbstractTask {

    private Logger logger = Logger.getLogger(this.getClass().getName());

    private File file;
    private RawDataFileType fileType;
    private MZmineProject project;
    private RawDataFileWriter newMZmineFile;
    private RawDataFile finalRawDataFile;

    private Process dumper = null;

    private int totalScans = 0, parsedScans = 0;

    /*
     * This array is used to set the number of fragments that one single scan
     * can have. The initial size of array is set to 10, but it depends of
     * fragmentation level.
     */
    private int parentTreeValue[] = new int[10];

    /*
     * This FIFO queue stores the scans until information about fragments is
     * added. After completing fragment info, the scans can be added to the raw
     * data file.
     */
    private LinkedList<SimpleScan> parentStack;

    /*
     * These variables are used during parsing of the RAW dump.
     */
    private int scanNumber = 0, msLevel = 0, precursorCharge = 0,
	    numOfDataPoints;
    private String scanId;
    private Polarity polarity;
    private Range<Double> mzRange;
    private double retentionTime = 0, precursorMZ = 0;

    public NativeFileReadTask(MZmineProject project, File fileToOpen,
	    RawDataFileType fileType, RawDataFileWriter newMZmineFile) {
	parentStack = new LinkedList<SimpleScan>();
	this.project = project;
	this.file = fileToOpen;
	this.fileType = fileType;
	this.newMZmineFile = newMZmineFile;
    }

    /**
     * @see net.sf.mzmine.taskcontrol.Task#getFinishedPercentage()
     */
    public double getFinishedPercentage() {
	return totalScans == 0 ? 0 : (double) parsedScans / totalScans;
    }

    /**
     * @see java.lang.Runnable#run()
     */
    public void run() {

	setStatus(TaskStatus.PROCESSING);
	logger.info("Opening file " + file);

	// Check the OS we are running
	String osName = System.getProperty("os.name").toUpperCase();
	String rawDumpPath;
	switch (fileType) {
	case THERMO_RAW:
	    rawDumpPath = System.getProperty("user.dir") + File.separator
		    + "lib" + File.separator + "vendor_lib" + File.separator
		    + "thermo" + File.separator + "ThermoRawDump.exe";
	    break;
	case WATERS_RAW:
	    rawDumpPath = System.getProperty("user.dir") + File.separator
		    + "lib" + File.separator + "vendor_lib" + File.separator
		    + "waters" + File.separator + "WatersRawDump.exe";
	    break;
	default:
	    throw new IllegalArgumentException(
		    "This module cannot open files of type " + fileType);
	}

	String cmdLine[];

	if (osName.toUpperCase().contains("WINDOWS")) {
	    cmdLine = new String[] { rawDumpPath, file.getPath() };
	} else {
	    cmdLine = new String[] { "wine", rawDumpPath, file.getPath() };
	}

	try {

	    // Create a separate process and execute RAWdump.exe
	    dumper = Runtime.getRuntime().exec(cmdLine);

	    // Get the stdout of RAWdump.exe process as InputStream
	    InputStream dumpStream = dumper.getInputStream();
	    BufferedInputStream bufStream = new BufferedInputStream(dumpStream);

	    // Read the dump data
	    readRAWDump(bufStream);

	    // Finish
	    bufStream.close();

	    if (isCanceled()) {
		dumper.destroy();
		return;
	    }

	    if (parsedScans == 0) {
		throw (new Exception("No scans found"));
	    }

	    if (parsedScans != totalScans) {
		throw (new Exception(
			"RAW dump process crashed before all scans were extracted ("
				+ parsedScans + " out of " + totalScans + ")"));
	    }

	    // Close file
	    finalRawDataFile = newMZmineFile.finishWriting();
	    project.addFile(finalRawDataFile);

	} catch (Throwable e) {

	    e.printStackTrace();

	    if (dumper != null)
		dumper.destroy();

	    if (getStatus() == TaskStatus.PROCESSING) {
		setStatus(TaskStatus.ERROR);
		setErrorMessage(ExceptionUtils.exceptionToString(e));
	    }

	    return;
	}

	logger.info("Finished parsing " + file + ", parsed " + parsedScans
		+ " scans");
	setStatus(TaskStatus.FINISHED);

    }

    public String getTaskDescription() {
	return "Opening file " + file;
    }

    /**
     * This method reads the dump of the RAW data file produced by RAWdump.exe
     * utility (see RAWdump.cpp source for details).
     */
    private void readRAWDump(InputStream dumpStream) throws IOException {

	String line;
	byte byteBuffer[] = new byte[100000];
	double mzValuesBuffer[] = new double[10000];
	double intensityValuesBuffer[] = new double[10000];

	while ((line = TextUtils.readLineFromStream(dumpStream)) != null) {

	    if (isCanceled()) {
		return;
	    }

	    if (line.startsWith("ERROR: ")) {
		throw (new IOException(line.substring("ERROR: ".length())));
	    }

	    if (line.startsWith("NUMBER OF SCANS: ")) {
		totalScans = Integer.parseInt(line
			.substring("NUMBER OF SCANS: ".length()));
	    }

	    if (line.startsWith("SCAN NUMBER: ")) {
		scanNumber = Integer.parseInt(line.substring("SCAN NUMBER: "
			.length()));
	    }

	    if (line.startsWith("SCAN ID: ")) {
		scanId = line.substring("SCAN ID: ".length());
	    }

	    if (line.startsWith("MS LEVEL: ")) {
		msLevel = Integer
			.parseInt(line.substring("MS LEVEL: ".length()));
	    }

	    if (line.startsWith("POLARITY: ")) {
		if (line.contains("-"))
		    polarity = Polarity.NEGATIVE;
		else if (line.contains("+"))
		    polarity = Polarity.POSITIVE;
		else
		    polarity = Polarity.UNKNOWN;
	    }

	    if (line.startsWith("RETENTION TIME: ")) {
		// Retention time is reported in minutes.
		retentionTime = Double.parseDouble(line
			.substring("RETENTION TIME: ".length()));
	    }

	    if (line.startsWith("PRECURSOR: ")) {
		String tokens[] = line.split(" ");
		double token2 = Double.parseDouble(tokens[1]);
		int token3 = Integer.parseInt(tokens[2]);
		if (token2 > 0) {
		    precursorMZ = token2;
		    precursorCharge = token3;
		}
	    }

	    if (line.startsWith("MASS VALUES: ")) {
		Pattern p = Pattern
			.compile("MASS VALUES: (\\d+) x (\\d+) BYTES");
		Matcher m = p.matcher(line);
		if (!m.matches())
		    throw new IOException("Could not parse line " + line);
		numOfDataPoints = Integer.parseInt(m.group(1));
		final int byteSize = Integer.parseInt(m.group(2));

		final int numOfBytes = numOfDataPoints * byteSize;
		if (byteBuffer.length < numOfBytes)
		    byteBuffer = new byte[numOfBytes * 2];
		dumpStream.read(byteBuffer, 0, numOfBytes);

		ByteBuffer mzByteBuffer = ByteBuffer.wrap(byteBuffer, 0,
			numOfBytes).order(ByteOrder.LITTLE_ENDIAN);
		if (mzValuesBuffer.length < numOfDataPoints)
		    mzValuesBuffer = new double[numOfDataPoints * 2];

		for (int i = 0; i < numOfDataPoints; i++) {
		    double newValue;
		    if (byteSize == 8)
			newValue = mzByteBuffer.getDouble();
		    else
			newValue = mzByteBuffer.getFloat();
		    mzValuesBuffer[i] = newValue;
		}

	    }

	    if (line.startsWith("INTENSITY VALUES: ")) {
		Pattern p = Pattern
			.compile("INTENSITY VALUES: (\\d+) x (\\d+) BYTES");
		Matcher m = p.matcher(line);
		if (!m.matches())
		    throw new IOException("Could not parse line " + line);
		// numOfDataPoints must be same for MASS VALUES and INTENSITY
		// VALUES
		if (numOfDataPoints != Integer.parseInt(m.group(1))) {
		    throw new IOException("Scan " + scanNumber + " contained "
			    + numOfDataPoints + " mass values, but "
			    + m.group(1) + " intensity values");
		}
		final int byteSize = Integer.parseInt(m.group(2));

		final int numOfBytes = numOfDataPoints * byteSize;
		if (byteBuffer.length < numOfBytes)
		    byteBuffer = new byte[numOfBytes * 2];
		dumpStream.read(byteBuffer, 0, numOfBytes);

		ByteBuffer intensityByteBuffer = ByteBuffer.wrap(byteBuffer, 0,
			numOfBytes).order(ByteOrder.LITTLE_ENDIAN);
		if (intensityValuesBuffer.length < numOfDataPoints)
		    intensityValuesBuffer = new double[numOfDataPoints * 2];
		for (int i = 0; i < numOfDataPoints; i++) {
		    double newValue;
		    if (byteSize == 8)
			newValue = intensityByteBuffer.getDouble();
		    else
			newValue = intensityByteBuffer.getFloat();
		    intensityValuesBuffer[i] = newValue;
		}

		// INTENSITY VALUES was the last item of the scan, so now we can
		// convert the data to DataPoint[] array and create a new scan

		DataPoint dataPoints[] = new DataPoint[numOfDataPoints];
		for (int i = 0; i < numOfDataPoints; i++) {
		    dataPoints[i] = new SimpleDataPoint(mzValuesBuffer[i],
			    intensityValuesBuffer[i]);
		}

		// Auto-detect whether this scan is centroided
		MassSpectrumType spectrumType = ScanUtils
			.detectSpectrumType(dataPoints);

		/*
		 * If this scan is a full scan (ms level = 1), it means that the
		 * previous scans stored in the stack, are complete and ready to
		 * be written to the raw data file.
		 */
		if (msLevel == 1) {
		    while (!parentStack.isEmpty()) {
			SimpleScan currentScan = parentStack.removeFirst();
			newMZmineFile.addScan(currentScan);
		    }
		}

		// Setting the current parentScan
		int parentScan = -1;
		if (msLevel > 1) {
		    parentScan = parentTreeValue[msLevel - 1];

		    if (!parentStack.isEmpty()) {
			for (SimpleScan s : parentStack) {
			    if (s.getScanNumber() == parentScan) {
				s.addFragmentScan(scanNumber);
			    }
			}
		    }
		}

		// Setting the parent scan number for this level of fragments
		parentTreeValue[msLevel] = scanNumber;

		SimpleScan newScan = new SimpleScan(null, scanNumber, msLevel,
			retentionTime, parentScan, precursorMZ,
			precursorCharge, null, dataPoints, spectrumType,
			polarity, scanId, mzRange);

		parentStack.add(newScan);
		parsedScans++;

		// Clean the variables for next scan
		scanNumber = 0;
		scanId = null;
		polarity = null;
		mzRange = null;
		msLevel = 0;
		retentionTime = 0;
		precursorMZ = 0;
		precursorCharge = 0;
		numOfDataPoints = 0;

	    }

	}

	// Add remaining scans in the parentStack
	while (!parentStack.isEmpty()) {
	    SimpleScan currentScan = parentStack.removeFirst();
	    newMZmineFile.addScan(currentScan);
	}

    }

    @Override
    public void cancel() {
	super.cancel();
	// Try to destroy the dumper process
	if (dumper != null) {
	    dumper.destroy();
	}
    }

}