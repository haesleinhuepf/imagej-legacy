/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2017 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package net.imagej.legacy.translate;

import ij.ImagePlus;
import ij.io.FileInfo;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.axis.DefaultLinearAxis;
import net.imagej.display.ImageDisplay;
import net.imagej.display.ImageDisplayService;
import net.imagej.legacy.LegacyImageMap;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.ImgView;
import net.imglib2.img.VirtualStackAdapter;
import net.imglib2.img.display.imagej.ImgPlusViews;
import net.imglib2.img.planar.PlanarImgFactory;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;
import org.scijava.AbstractContextual;
import org.scijava.Context;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;

/**
 * Class to create a {@link Dataset} which is linked to an {@link ImagePlus}.
 *
 * @author Mark Hiner
 * @author Matthias Arzt
 */
public class DisplayCreator extends AbstractContextual
{
	@Parameter
	private DatasetService datasetService;

	@Parameter
	private ImageDisplayService imageDisplayService;

	private final ColorTableHarmonizer colorTableHarmonizer;
	private final MetadataHarmonizer metadataHarmonizer;
	private final CompositeHarmonizer compositeHarmonizer;
	private final OverlayHarmonizer overlayHarmonizer;
	private final PositionHarmonizer positionHarmonizer;
	private final NameHarmonizer nameHarmonizer;

	@Parameter
	private DisplayService displayService;

	public DisplayCreator( final Context context )
	{
		setContext(context);
		nameHarmonizer = new NameHarmonizer();
		metadataHarmonizer = new MetadataHarmonizer();
		overlayHarmonizer = new OverlayHarmonizer(context);
		positionHarmonizer = new PositionHarmonizer();
		compositeHarmonizer = new CompositeHarmonizer();
		colorTableHarmonizer = new ColorTableHarmonizer(imageDisplayService);
	}

	public ImageDisplay createDisplay(final ImagePlus imp) {

		return createDisplay(imp, LegacyUtils.getPreferredAxisOrder());
	}

	public ImageDisplay createDisplay(final ImagePlus imp,
		final AxisType[] preferredOrder)
	{
		return makeDisplay(imp, preferredOrder);
	}

	/**
	 * @return A {@link Dataset} appropriate for the given {@link ImagePlus}
	 */
	protected Dataset getDataset(final ImagePlus imp,
		final AxisType[] preferredOrder)
	{
		final Dataset ds = makeDataset(imp, preferredOrder);
		ds.getProperties().put(LegacyImageMap.IMP_KEY, imp);
		final FileInfo fileInfo = imp.getOriginalFileInfo();
		String source = "";
		if (fileInfo == null) {
			// If no original file info, just use the title. This may be the case
			// when an ImagePlus is created as the output of a command.
			source = imp.getTitle();
		}
		else {
			if (fileInfo.url == null || fileInfo.url.isEmpty()) {
				source = fileInfo.directory + fileInfo.fileName;
			}
			else {
				source = fileInfo.url;
			}
		}
		ds.getImgPlus().setSource(source);
		return ds;
	}

	protected ImageDisplay harmonizeExceptPixels( ImagePlus imp, Dataset ds )
	{
		metadataHarmonizer.updateDataset(ds, imp);
		compositeHarmonizer.updateDataset(ds, imp);

		// CTR FIXME - add imageDisplayService.createImageDisplay method?
		// returns null if it cannot find an ImageDisplay-compatible display?
		final ImageDisplay display =
			(ImageDisplay) displayService.createDisplay(ds.getName(), ds);

		colorTableHarmonizer.updateDisplay(display, imp);
		// NB - correct thresholding behavior requires overlay harmonization after
		// color table harmonization
		overlayHarmonizer.updateDisplay(display, imp);
		positionHarmonizer.updateDisplay(display, imp);
		nameHarmonizer.updateDisplay(display, imp);
		return display;
	}

	protected Dataset makeDataset(ImagePlus imp, AxisType[] preferredOrder) {
		ImgPlus imgPlus = toImgPlus( imp, preferredOrder );
		final Dataset ds = datasetService.create( imgPlus );
		DatasetUtils.initColorTables(ds);
		ds.setRGBMerged( imp.getType() == ImagePlus.COLOR_RGB && imp.getNChannels() == 1);
		return ds;
	}

	private ImgPlus toImgPlus( ImagePlus imp, AxisType[] preferredOrder )
	{
		return wrap( imp );
	}

	private ImgPlus< ? > wrap( ImagePlus imp )
	{
		if (imp.getType() == ImagePlus.COLOR_RGB) {
			ImgPlus<ARGBType> colored = VirtualStackAdapter.wrapRGBA( imp );
			// TODO: This special treatment of Img<ARGBType> is wrongly placed.
			return splitColorChannels(colored);
		}
		else {
			return VirtualStackAdapter.wrap( imp );
		}
	}

	private ImgPlus<UnsignedByteType> splitColorChannels(ImgPlus<ARGBType> input) {
		Img<ARGBType> colored = input.getImg();
		RandomAccessibleInterval<UnsignedByteType> colorStack = Views.stack(
				Converters.argbChannel( colored, 1 ),
				Converters.argbChannel( colored, 2 ),
				Converters.argbChannel( colored, 3 ) );
		ImgPlus<UnsignedByteType> result = new ImgPlus<>(ImgView.wrap(colorStack, new PlanarImgFactory<>()), input.getName());
		int lastAxis = colored.numDimensions();
		for (int i = 0; i < lastAxis; i++) result.setAxis(input.axis(i).copy(), i);
		result.setAxis(new DefaultLinearAxis(Axes.CHANNEL), lastAxis);
		return ImgPlusViews.moveAxis(result, lastAxis, 2);
	}

	/**
	 * @return An {@link ImageDisplay} created from the given {@link ImagePlus}
	 */
	private ImageDisplay makeDisplay( ImagePlus imp, AxisType[] preferredOrder ) {
		Dataset ds = getDataset(imp, preferredOrder);
		return harmonizeExceptPixels( imp, ds );
	}
}