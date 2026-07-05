from merger import videoReading
from merger import mergeStaticImgWithVideo
from merger import removeGreenScreen
from merger import removeGreenScreenOfPath


ricePouringPath = "uploads/whiteRicePouring.mp4"
waterPouringPath = "uploads/waterPouring.mp4"
bowlPath = "uploads/bowlWithGreenScreen.png"

ricePouring50DPath = "uploads/ricePouring50DegreeAngle.mp4"
waterPouring50DPath = "uploads/waterPouring50DegreeAngle.mp4"
bowlPath50D = "uploads/ImageStainlessSteel50Degree.webp"
cockingPan50DPath = "uploads/cookingPan50D.webp"

#removeGreenScreenOfPath(cockingPan50DPath)

#removeGreenScreen("uploads/bowlWithGreenScreen.png")
#videoReading("uploads/waterPouring.mp4")
#mergeStaticImgWithVideo(bowlPath,waterPouringPath)
#mergeStaticImgWithVideo(bowlPath,ricePouringPath)
mergeStaticImgWithVideo(cockingPan50DPath,ricePouringPath)