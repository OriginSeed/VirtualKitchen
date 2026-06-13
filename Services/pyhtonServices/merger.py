import cv2
import numpy as np


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