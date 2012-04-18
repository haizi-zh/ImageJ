package org.micromanager.acquisition;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

import java.util.logging.Level;
import java.util.logging.Logger;
import mmcorej.TaggedImage;
import org.json.JSONException;
import org.json.JSONObject;
import org.micromanager.api.AcquisitionEngine;
import org.micromanager.api.DataProcessor;
import org.micromanager.utils.MDUtils;

import org.micromanager.utils.MMScriptException;
import org.micromanager.utils.ReportingUtils;

public class AcquisitionManager {
	Hashtable<String, MMAcquisition> acqs_;
	private String album_ = null;
	// Offer a pipeline to analyze acquired images on the fly
	private List<DataProcessor<TaggedImage>> taggedImageProcessors_;

	public void addImageProcessor(
			DataProcessor<TaggedImage> taggedImageProcessor) {
		boolean exist = false;
		for (DataProcessor<TaggedImage> proc : taggedImageProcessors_) {
			if (proc.getClass() == taggedImageProcessor.getClass()) {
				exist = true;
				break;
			}
		}
		if (!exist)
			taggedImageProcessors_.add(taggedImageProcessor);
	}

	/**
	 * @return the taggedImageProcessors_
	 */
	public List<DataProcessor<TaggedImage>> getTaggedImageProcessors() {
		return taggedImageProcessors_;
	}

	public void removeImageProcessor(
			DataProcessor<TaggedImage> taggedImageProcessor) {
		for (DataProcessor<TaggedImage> proc : taggedImageProcessors_) {
			if (proc.getClass() == taggedImageProcessor.getClass())
				taggedImageProcessors_.remove(proc);
		}
	}

	public AcquisitionManager() {
		acqs_ = new Hashtable<String, MMAcquisition>();
		taggedImageProcessors_ = new ArrayList<DataProcessor<TaggedImage>>();
	}

	public void openAcquisition(String name, String rootDir)
			throws MMScriptException {
		if (acquisitionExists(name))
			throw new MMScriptException("The name is in use");
		else {
			MMAcquisition acq = new MMAcquisition(name, rootDir);
			acq.setTaggedImageProcessors(taggedImageProcessors_);
			acqs_.put(name, acq);
		}
	}

	public void openAcquisition(String name, String rootDir, boolean show)
			throws MMScriptException {
		this.openAcquisition(name, rootDir, show, false);
	}

	public void openAcquisition(String name, String rootDir, boolean show,
			boolean diskCached) throws MMScriptException {
		this.openAcquisition(name, rootDir, show, diskCached, false);
	}

	public void openAcquisition(String name, String rootDir, boolean show,
			boolean diskCached, boolean existing) throws MMScriptException {
		if (acquisitionExists(name)) {
			throw new MMScriptException("The name is in use");
		} else {
			MMAcquisition acq = new MMAcquisition(name, rootDir, show,
					diskCached, existing);
			acq.setTaggedImageProcessors(taggedImageProcessors_);
			acqs_.put(name, acq);
		}
	}

	public void closeAcquisition(String name) throws MMScriptException {
		if (!acqs_.containsKey(name))
			throw new MMScriptException("The name does not exist");
		else {
			acqs_.get(name).close();
			acqs_.remove(name);
		}
	}

	public void closeImage5D(String name) throws MMScriptException {
		if (!acquisitionExists(name))
			throw new MMScriptException("The name does not exist");
		else
			acqs_.get(name).closeImage5D();
	}

	public Boolean acquisitionExists(String name) {
		if (acqs_.containsKey(name)) {
			if (acqs_.get(name).windowClosed()) {
				acqs_.get(name).close();
				acqs_.remove(name);
				return false;
			}
			return true;
		}
		return false;
	}

	public boolean hasActiveImage5D(String name) throws MMScriptException {
		if (acquisitionExists(name)) {
			return !acqs_.get(name).windowClosed();
		}
		return false;
	}

	public MMAcquisition getAcquisition(String name) throws MMScriptException {
		if (acquisitionExists(name))
			return acqs_.get(name);
		else
			throw new MMScriptException("Undefined acquisition name: " + name);
	}

	public void closeAll() {
		for (Enumeration<MMAcquisition> e = acqs_.elements(); e
				.hasMoreElements();)
			e.nextElement().close();

		acqs_.clear();
	}

	public String getUniqueAcquisitionName(String name) {
		char separator = '_';
		while (acquisitionExists(name)) {
			int lastSeparator = name.lastIndexOf(separator);
			if (lastSeparator == -1)
				name += separator + "1";
			else {
				Integer i = Integer.parseInt(name.substring(lastSeparator + 1));
				i++;
				name = name.substring(0, lastSeparator) + separator + i;
			}
		}
		return name;
	}

	public String getCurrentAlbum() {
		if (album_ == null) {
			return createNewAlbum();
		} else {
			return album_;
		}
	}

	public String createNewAlbum() {
		album_ = getUniqueAcquisitionName("Album");
		return album_;
	}

	public String addToAlbum(TaggedImage image) throws MMScriptException {
		boolean newNeeded = true;
		MMAcquisition acq = null;
		String album = getCurrentAlbum();
		JSONObject tags = image.tags;
		int imageWidth, imageHeight, imageDepth, imageBitDepth, numChannels;

		try {
			imageWidth = MDUtils.getWidth(tags);
			imageHeight = MDUtils.getHeight(tags);
			imageDepth = MDUtils.getDepth(tags);
			imageBitDepth = MDUtils.getBitDepth(tags);
			// need to check umber of channels so that multi cam and single cam
			// acquistions of same size and depth are differentiated
			numChannels = MDUtils.getNumChannels(tags);

		} catch (Exception e) {
			throw new MMScriptException("Something wrong with image tags.");
		}

		if (acquisitionExists(album)) {
			acq = acqs_.get(album);
			try {
				if (acq.getWidth() == imageWidth
						&& acq.getHeight() == imageHeight
						&& acq.getDepth() == imageDepth
						&& acq.getMultiCameraNumChannels() == numChannels
						&& !acq.getImageCache().isFinished())
					newNeeded = false;
			} catch (Exception e) {
			}
		}

		if (newNeeded) {
			album = createNewAlbum();
			openAcquisition(album, "", true, false);
			acq = getAcquisition(album);
			acq.setDimensions(2, numChannels, 1, 1);
			acq.setImagePhysicalDimensions(imageWidth, imageHeight, imageDepth,
					imageBitDepth, numChannels);

			try {
				JSONObject summary = new JSONObject();
				summary.put("PixelType", tags.get("PixelType"));
				acq.setSummaryProperties(summary);
			} catch (JSONException ex) {
				ex.printStackTrace();
			}

			acq.initialize();
		}

		int f = 1 + acq.getLastAcquiredFrame();
		if (numChannels > 1) {
			try { // assumes that multi channel additions add channel 0 first
				JSONObject lastTags = acq.getImageCache().getLastImageTags();
				int lastCh = -1;
				if (lastTags != null)
					lastCh = MDUtils.getChannelIndex(lastTags);
				if (lastCh == 0)
					f = acq.getLastAcquiredFrame();
			} catch (JSONException ex) {
				ReportingUtils.logError(ex);
			}
		}
		try {
			MDUtils.setFrameIndex(image.tags, f);
		} catch (JSONException ex) {
			ReportingUtils.showError(ex);
		}
		acq.insertImage(image);

		return album;
	}

	public String[] getAcqusitionNames() {
		Set<String> keySet = acqs_.keySet();
		String keys[] = new String[keySet.size()];
		return keySet.toArray(keys);
	}

	public String createAcquisition(JSONObject summaryMetadata,
			boolean diskCached, AcquisitionEngine engine) {
		String name = this.getUniqueAcquisitionName("Acq");
		MMAcquisition acq = new MMAcquisition(name, summaryMetadata,
				diskCached, engine);
		acq.setTaggedImageProcessors(taggedImageProcessors_);
		acqs_.put(name, acq);
		return name;
	}
}