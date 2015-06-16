# @File(label = "Input directory", style = "directory") srcFile
# @File(label = "Output directory", style = "directory") dstFile
# @String(label = "File extension", value=".tif") ext
# @String(label = "File name contains", value = "") containString
# @boolean(label = "Keep directory structure when saving", value = true) keepDirectories

# Compare with the original Process_Folder to see how ImageJ 1.x
# GenericDialog use can be converted to @Parameters.

import os
from ij import IJ, ImagePlus

def run():
  srcDir = srcFile.getAbsolutePath()
  dstDir = dstFile.getAbsolutePath()
  for root, directories, filenames in os.walk(srcDir):
    for filename in filenames:
      # Check for file extension
      if not filename.endswith(ext):
        continue
      # Check for file name pattern
      if containString not in filename:
        continue
      process(srcDir, dstDir, root, filename, keepDirectories)
 
def process(srcDir, dstDir, currentDir, fileName, keepDirectories):
  print "Processing:"
   
  # Opening the image
  print "Open image file", fileName
  imp = IJ.openImage(os.path.join(currentDir, fileName))
   
  # Put your processing commands here!
   
  # Saving the image
  saveDir = currentDir.replace(srcDir, dstDir) if keepDirectories else dstDir
  if not os.path.exists(saveDir):
    os.makedirs(saveDir)
  print "Saving to", saveDir
  IJ.saveAs(imp, "Tiff", os.path.join(saveDir, fileName));
  imp.close()
 
run()