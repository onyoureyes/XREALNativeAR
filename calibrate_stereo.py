import cv2
import numpy as np
import glob
import os
import argparse

def calibrate_stereo(image_dir, rows, cols, square_size):
    # Pattern size (inner corners)
    pattern_size = (cols, rows)
    
    # Prepare object points, like (0,0,0), (1,0,0), (2,0,0) ....,(6,5,0)
    objp = np.zeros((rows * cols, 3), np.float32)
    objp[:, :2] = np.mgrid[0:cols, 0:rows].T.reshape(-1, 2) * square_size
    
    # Arrays to store object points and image points from all the images.
    objpoints = [] # 3d point in real world space
    imgpoints_left = [] # 2d points in image plane.
    imgpoints_right = []
    
    left_images = sorted(glob.glob(os.path.join(image_dir, '*_left.png')))
    right_images = sorted(glob.glob(os.path.join(image_dir, '*_right.png')))
    
    if len(left_images) == 0:
        print("No images found in", image_dir)
        return
        
    print(f"Found {len(left_images)} image pairs. Searching for {cols}x{rows} corners...")
    
    img_shape = None
    
    valid_pairs = 0
    
    for left_path, right_path in zip(left_images, right_images):
        img_l = cv2.imread(left_path, cv2.IMREAD_GRAYSCALE)
        img_r = cv2.imread(right_path, cv2.IMREAD_GRAYSCALE)
        
        # Apply CLAHE to improve contrast on dark SLAM camera images
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8,8))
        img_l = clahe.apply(img_l)
        img_r = clahe.apply(img_r)
        
        # Use the Sector Based (SB) algorithm which is MUCH more robust to fisheye and severe distortion
        # It doesn't need external flags, it handles everything internally
        
        if img_shape is None:
            img_shape = img_l.shape[::-1] # (width, height)
            
        # Try exactly the pattern size requested by the user.
        # Allowing subsets like (8,5) on a (9,6) board breaks stereo 
        # calibration if left and right cameras find DIFFERENT (8,5) subsets!
        patterns_to_try = [(cols, rows)]
        
        ret_l, corners_l = False, None
        ret_r, corners_r = False, None
        found_pattern = None
        
        for p_size in patterns_to_try:
            ret_l, corners_l = cv2.findChessboardCornersSB(img_l, p_size, cv2.CALIB_CB_EXHAUSTIVE | cv2.CALIB_CB_ACCURACY)
            ret_r, corners_r = cv2.findChessboardCornersSB(img_r, p_size, cv2.CALIB_CB_EXHAUSTIVE | cv2.CALIB_CB_ACCURACY)
            
            if ret_l and ret_r:
                found_pattern = p_size
                break
        if found_pattern is not None:
            # Prepare object points for the SPECIFIC pattern found
            curr_cols, curr_rows = found_pattern
            curr_objp = np.zeros((curr_rows * curr_cols, 3), np.float32)
            curr_objp[:, :2] = np.mgrid[0:curr_cols, 0:curr_rows].T.reshape(-1, 2) * square_size
            
            valid_pairs += 1
            objpoints.append(curr_objp)
            
            # SB algorithm inherently refines corners, so cornerSubPix is optional
            # but we can do it for max accuracy
            criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001)
            corners_l_refined = cv2.cornerSubPix(img_l, corners_l, (11, 11), (-1, -1), criteria)
            corners_r_refined = cv2.cornerSubPix(img_r, corners_r, (11, 11), (-1, -1), criteria)
            
            imgpoints_left.append(corners_l_refined)
            imgpoints_right.append(corners_r_refined)
            # Draw and save for debug
            cv2.drawChessboardCorners(img_l, found_pattern, corners_l_refined, ret_l)
            cv2.imwrite(f"{left_path}_debug_corners_SB.jpg", img_l)
            print(f"OK: Found {found_pattern} corners in {os.path.basename(left_path)}")
        else:
            # We don't save all failures anymore to reduce spam, just note it
            print(f"x Failed to find ANY corners in {os.path.basename(left_path)}")
            
    if valid_pairs < 5:
        print(f"Not enough valid pairs found ({valid_pairs}). Calibration requires at least 5-10 good pairs.")
        return
        
    print(f"\nCalibrating with {valid_pairs} valid pairs...")
    
    # Disable some flags for fisheye/wide angle if standard calibration fails, 
    # but let's try standard first with rational model for heavy distortion
    calib_flags = cv2.CALIB_RATIONAL_MODEL
    
    # Calibrate individual cameras first
    print("Calibrating Left Camera...")
    ret_l, K1, D1, R1_ind, T1_ind = cv2.calibrateCamera(objpoints, imgpoints_left, img_shape, None, None, flags=calib_flags)
    print("Calibrating Right Camera...")
    ret_r, K2, D2, R2_ind, T2_ind = cv2.calibrateCamera(objpoints, imgpoints_right, img_shape, None, None, flags=calib_flags)
    
    # Stereo Calibration using standard lens models first
    print("Performing Stereo Calibration...")
    criteria_stereo = (cv2.TERM_CRITERIA_MAX_ITER + cv2.TERM_CRITERIA_EPS, 100, 1e-5)
    
    # Enable rational model for heavy distortion, and fix intrinsic so we just calculate R and T
    stereo_flags = cv2.CALIB_FIX_INTRINSIC
    
    ret_S, K1, D1, K2, D2, R, T, E, F = cv2.stereoCalibrate(
        objpoints, imgpoints_left, imgpoints_right,
        K1, D1, K2, D2,
        img_shape,
        criteria=criteria_stereo,
        flags=stereo_flags
    )
    
    print(f"\nStereo Calibration RMS Error: {ret_S:.4f}")
    
    # Stereo Rectification
    print("Calculating Rectification Matrices...")
    R1, R2, P1, P2, Q, roi_left, roi_right = cv2.stereoRectify(
        K1, D1, K2, D2, img_shape, R, T, 
        flags=cv2.CALIB_ZERO_DISPARITY, alpha=0.0
    )
    
    # Format output for Kotlin
    print("\n" + "="*50)
    print("KOTLIN READY CONSTANTS")
    print("="*50)
    
    def print_mat(name, mat):
        flat = ", ".join([f"{v:.5f}" for v in mat.flatten()])
        print(f"val {name} = doubleArrayOf({flat})")
        
    print_mat("K1", K1)
    print_mat("D1", D1)
    print_mat("K2", K2)
    print_mat("D2", D2)
    print_mat("R1", R1)
    print_mat("P1", P1)
    print_mat("R2", R2)
    print_mat("P2", P2)
    print("="*50)
    
    # Save the parameters for python reuse if needed
    np.savez('stereo_calib.npz', K1=K1, D1=D1, K2=K2, D2=D2, R1=R1, R2=R2, P1=P1, P2=P2)
    print("Saved matrices to stereo_calib.npz")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Stereo Calibration')
    parser.add_argument('--dir', type=str, default='calibration_metadata/Pictures', help='Directory with left/right images')
    parser.add_argument('--cols', type=int, default=9, help='Number of inner corners horizontally')
    parser.add_argument('--rows', type=int, default=6, help='Number of inner corners vertically')
    parser.add_argument('--size', type=float, default=25.0, help='Size of square in mm')
    
    args = parser.parse_args()
    calibrate_stereo(args.dir, args.rows, args.cols, args.size)
