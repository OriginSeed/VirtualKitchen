import cv2
import numpy as np

def printImage(img):
    cv2.imshow("Preview", img)
    cv2.waitKey(0)
    cv2.destroyAllWindows()

def changeCameraAngle(img):
    h, w = img.shape[:2]

    src = np.float32([
        [0, 0],
        [w, 0],
        [0, h],
        [w, h]
    ])

    # Simulate camera tilting downward
    # dst = np.float32([
    #     [100, 50],
    #     [w - 100, 0],
    #     [0, h],
    #     [w, h - 50]
    # ])

    # # left camera angle
    # dst = np.float32([
    #     [150, 0],
    #     [w, 50],
    #     [0, h - 50],
    #     [w - 100, h]
    # ])

    # #right camera angle
    # dst = np.float32([
    #     [0, 50],
    #     [w - 150, 0],
    #     [100, h],
    #     [w, h - 50]
    # ])

    #Top-down look
    dst = np.float32([
        [80, 80],
        [w - 80, 80],
        [0, h],
        [w, h]
    ])
    #
    # #Dramatic perspective
    # dst = np.float32([
    #     [200, 100],
    #     [w - 200, 50],
    #     [0, h],
    #     [w, h]
    # ])

    matrix = cv2.getPerspectiveTransform(src, dst)

    return cv2.warpPerspective(img, matrix, (w, h))
def removeGreenScreenOfPath(imgPath):
    img = cv2.imread(imgPath)
    return removeGreenScreen(img)

def removeGreenScreen(img):
    FRAME_WIDTH = 800
    FRAME_HEIGHT = 600
    img = cv2.resize(img, (FRAME_WIDTH, FRAME_HEIGHT))
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    lower_green = np.array([40, 40, 10])
    upper_green = np.array([75, 255, 255])
    green_mask = cv2.inRange(hsv, lower_green, upper_green)
    non_screen_mask = cv2.bitwise_not(green_mask)
    imgWater = cv2.bitwise_and(img, img, mask=non_screen_mask)
    #printImage(imgWater)
    return imgWater

def videoReading(videoPath):
    video = cv2.VideoCapture(videoPath)
    while True:
        success, frame = video.read()
        if not success:
            break
        cv2.imshow("Video", frame)
        if cv2.waitKey(30) == ord('q'):
            break
    video.release()
    cv2.destroyAllWindows()

def mergeStaticImgWithVideo(imagePath, videoPath):
    bowl = cv2.imread(imagePath)
    bowl = removeGreenScreen(bowl)
    video = cv2.VideoCapture(videoPath)
    pause = False
    while True:
        if not pause:
            success, frame = video.read()
            if not success:
                break
            frame = removeGreenScreen(frame)
            merged = cv2.add(frame, bowl)
            #merged[np.all(merged == [0, 0, 0], axis=-1)] = (0,255, 0)
            #merged = changeCameraAngle(merged)
            cv2.imshow("Video", merged)
        key = cv2.waitKey(30) & 0xFF
        if key == ord('q'):
            break
        elif key == ord(' '):
            pause = not pause
    video.release()
    cv2.destroyAllWindows()

def merge_video(bowl_image_path, water_video_path, output_video_path):
    BOWL_X = 180
    BOWL_Y = 220
    BOWL_W = 280
    BOWL_H = 120

    bowl_bg = cv2.imread(bowl_image_path)
    water_video = cv2.VideoCapture(water_video_path)
    fps = water_video.get(cv2.CAP_PROP_FPS)
    width = int(
        water_video.get(cv2.CAP_PROP_FRAME_WIDTH)
    )

    height = int(
        water_video.get(cv2.CAP_PROP_FRAME_HEIGHT)
    )

    bowl_bg = cv2.resize(
        bowl_bg,
        (width, height)
    )

    fourcc = cv2.VideoWriter_fourcc(*'mp4v')

    writer = cv2.VideoWriter(
        output_video_path,
        fourcc,
        fps,
        (width, height)
    )

    while True:
        ret, water_frame = water_video.read()
        if not ret:
            break

        hsv = cv2.cvtColor(
            water_frame,
            cv2.COLOR_BGR2HSV
        )

        lower_green = np.array([35, 40, 40])
        upper_green = np.array([85, 255, 255])

        # Detect green screen
        green_mask = cv2.inRange(
            hsv,
            lower_green,
            upper_green
        )

        # Water = everything except green
        water_mask = cv2.bitwise_not(
            green_mask
        )

        # Bowl mask
        bowl_roi_mask = np.zeros(
            (height, width),
            dtype=np.uint8
        )

        cv2.ellipse(
            bowl_roi_mask,
            (
                BOWL_X + BOWL_W // 2,
                BOWL_Y + BOWL_H // 2
            ),
            (
                BOWL_W // 2,
                BOWL_H // 2
            ),
            0,
            0,
            360,
            255,
            -1
        )

        # Keep water stream ABOVE bowl
        stream_mask = water_mask.copy()

        stream_mask[BOWL_Y:, :] = 0

        # Keep water INSIDE bowl
        bowl_water_mask = cv2.bitwise_and(
            water_mask,
            bowl_roi_mask
        )

        # Final visible water
        final_mask = cv2.bitwise_or(
            stream_mask,
            bowl_water_mask
        )

        # Compose result
        combined = bowl_bg.copy()

        combined[final_mask > 0] = water_frame[
            final_mask > 0
        ]
        writer.write(combined)

    writer.release()
    water_video.release()