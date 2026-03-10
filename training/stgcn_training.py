#!/usr/bin/env python3
"""
ST-GCN (Spatial Temporal Graph Convolutional Network) 행동 분류 학습 파이프라인.

CenterNet 17-keypoint 스켈레톤 시퀀스 → 10가지 행동 분류.

## 행동 클래스
0: SITTING, 1: STANDING, 2: WALKING, 3: RUNNING, 4: REACHING,
5: WRITING, 6: BENDING, 7: WAVING, 8: EATING, 9: EXERCISING

## 입력
- Shape: [batch, frames, 17, 2] (x, y 좌표, 정규화 0~1)
- frames: 15 (0.5초 @30fps)

## 출력
- Shape: [batch, 10] (행동 클래스 logits)
- TFLite INT8 양자화 → 안드로이드 배포

## 사용법
1. 실제 데이터 수집 전: python stgcn_training.py --synthetic
2. 데이터 수집 후: python stgcn_training.py --data_dir ./action_data/
3. TFLite 변환: python stgcn_training.py --convert_only --checkpoint model.pth
"""

import argparse
import numpy as np
import os
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader

# ─── 설정 ───
NUM_KEYPOINTS = 17
NUM_COORDS = 2  # x, y
WINDOW_SIZE = 15
NUM_CLASSES = 10
ACTION_LABELS = [
    "SITTING", "STANDING", "WALKING", "RUNNING", "REACHING",
    "WRITING", "BENDING", "WAVING", "EATING", "EXERCISING"
]

# CenterNet 17-keypoint 골격 연결 (adjacency)
SKELETON_EDGES = [
    (0, 1),   # nose-neck
    (1, 2), (1, 3),   # neck-shoulders
    (2, 4), (3, 5),   # shoulders-elbows
    (4, 6), (5, 7),   # elbows-wrists
    (1, 8), (1, 9),   # neck-hips
    (8, 10), (9, 11),  # hips-knees
    (10, 12), (11, 13),  # knees-ankles
    (0, 14), (0, 15),  # nose-eyes
    (0, 16),  # nose-ear
]


# ─── ST-GCN 모델 ───

class GraphConv(nn.Module):
    """그래프 합성곱 레이어 (인접 행렬 기반)."""
    def __init__(self, in_ch, out_ch, adj):
        super().__init__()
        self.adj = adj  # [V, V]
        self.conv = nn.Conv2d(in_ch, out_ch, kernel_size=1)
        self.bn = nn.BatchNorm2d(out_ch)

    def forward(self, x):
        # x: [B, C, T, V]
        # 그래프 합성곱: A @ X
        x = torch.einsum('bcti,ij->bctj', x, self.adj.to(x.device))
        x = self.conv(x)
        x = self.bn(x)
        return F.relu(x)


class STGCNBlock(nn.Module):
    """Spatial-Temporal Graph Convolution block."""
    def __init__(self, in_ch, out_ch, adj, stride=1):
        super().__init__()
        self.gcn = GraphConv(in_ch, out_ch, adj)
        # 시간축 합성곱 (kernel_size=3)
        self.tcn = nn.Sequential(
            nn.Conv2d(out_ch, out_ch, kernel_size=(3, 1), padding=(1, 0), stride=(stride, 1)),
            nn.BatchNorm2d(out_ch),
        )
        self.relu = nn.ReLU(inplace=True)
        # 잔차 연결
        self.residual = nn.Sequential(
            nn.Conv2d(in_ch, out_ch, kernel_size=1, stride=(stride, 1)),
            nn.BatchNorm2d(out_ch),
        ) if in_ch != out_ch or stride != 1 else nn.Identity()

    def forward(self, x):
        res = self.residual(x)
        x = self.gcn(x)
        x = self.tcn(x)
        return self.relu(x + res)


class STGCN(nn.Module):
    """ST-GCN: 경량 행동 분류 네트워크.

    입력: [B, 2, T, 17] (x,y 좌표 × 프레임 × 키포인트)
    출력: [B, NUM_CLASSES]
    """
    def __init__(self, num_classes=NUM_CLASSES):
        super().__init__()
        adj = self._build_adjacency()
        self.register_buffer('adj', adj)

        self.layers = nn.Sequential(
            STGCNBlock(2, 32, adj),
            STGCNBlock(32, 32, adj),
            STGCNBlock(32, 64, adj, stride=2),
            STGCNBlock(64, 64, adj),
            STGCNBlock(64, 128, adj, stride=2),
        )
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.fc = nn.Linear(128, num_classes)

    def _build_adjacency(self):
        """인접 행렬 생성 (자기 루프 포함, 정규화)."""
        adj = torch.zeros(NUM_KEYPOINTS, NUM_KEYPOINTS)
        for i, j in SKELETON_EDGES:
            adj[i, j] = 1
            adj[j, i] = 1
        adj += torch.eye(NUM_KEYPOINTS)  # 자기 루프
        # 정규화: D^(-1/2) A D^(-1/2)
        d = adj.sum(dim=1).clamp(min=1)
        d_inv_sqrt = torch.diag(d.pow(-0.5))
        adj = d_inv_sqrt @ adj @ d_inv_sqrt
        return adj

    def forward(self, x):
        # x: [B, T, V, C] → [B, C, T, V]
        x = x.permute(0, 3, 1, 2).contiguous()
        x = self.layers(x)
        x = self.pool(x).squeeze(-1).squeeze(-1)
        return self.fc(x)


# ─── 합성 데이터 생성 ───

def generate_synthetic_data(num_samples=2000, window=WINDOW_SIZE):
    """교사 일상 행동 합성 스켈레톤 데이터."""
    X = []
    y = []

    for _ in range(num_samples):
        label = np.random.randint(0, NUM_CLASSES)
        skeleton_seq = _generate_action_skeleton(label, window)
        X.append(skeleton_seq)
        y.append(label)

    return np.array(X, dtype=np.float32), np.array(y, dtype=np.int64)


def _generate_action_skeleton(action_id, window):
    """행동별 합성 스켈레톤 시퀀스 생성."""
    seq = np.zeros((window, NUM_KEYPOINTS, 2), dtype=np.float32)

    # 기본 직립 자세 (정규화 좌표)
    base = _standing_pose()

    for t in range(window):
        pose = base.copy()
        phase = t / window * 2 * np.pi

        if action_id == 0:  # SITTING
            pose[8:10, 1] -= 0.05  # 엉덩이 약간 아래
            pose[10:12, 1] -= 0.03  # 무릎 굽힘
            pose[10:12, 0] += 0.05  # 무릎 앞으로

        elif action_id == 1:  # STANDING
            pose += np.random.randn(*pose.shape) * 0.003  # 미세 흔들림

        elif action_id == 2:  # WALKING
            stride_offset = np.sin(phase) * 0.04
            pose[12, 0] += stride_offset  # L ankle
            pose[13, 0] -= stride_offset  # R ankle
            pose[10, 0] += stride_offset * 0.5
            pose[11, 0] -= stride_offset * 0.5
            # 팔 스윙
            pose[6, 0] -= stride_offset * 0.3
            pose[7, 0] += stride_offset * 0.3

        elif action_id == 3:  # RUNNING
            stride_offset = np.sin(phase * 2) * 0.08
            pose[12, 0] += stride_offset
            pose[13, 0] -= stride_offset
            pose[6, 0] -= stride_offset * 0.5
            pose[7, 0] += stride_offset * 0.5
            pose[:, 1] += np.sin(phase * 2) * 0.02  # 상하 바운스

        elif action_id == 4:  # REACHING
            arm = np.random.choice([6, 7])  # 랜덤 손
            pose[arm, 0] += 0.15
            pose[arm, 1] -= 0.10
            if arm == 6:
                pose[4, 0] += 0.08
            else:
                pose[5, 0] += 0.08

        elif action_id == 5:  # WRITING
            # 손목이 몸 앞, 미세 움직임
            pose[6, 1] += 0.05
            pose[7, 1] += 0.05
            pose[6, 0] += np.sin(phase * 3) * 0.01
            pose[7, 0] += np.sin(phase * 3) * 0.01

        elif action_id == 6:  # BENDING
            bend = 0.08
            pose[0, 1] += bend  # 머리 아래로
            pose[1, 1] += bend * 0.7
            pose[2:4, 1] += bend * 0.5

        elif action_id == 7:  # WAVING
            arm = np.random.choice([6, 7])
            pose[arm, 1] -= 0.20  # 머리 위
            pose[arm, 0] += np.sin(phase * 4) * 0.08  # 좌우 흔들기

        elif action_id == 8:  # EATING
            # 손이 얼굴 근처 + 반복
            pose[6, 1] -= 0.10
            pose[6, 0] += 0.02
            pose[6, 1] += np.sin(phase * 2) * 0.03

        elif action_id == 9:  # EXERCISING
            # 전체 움직임 큼
            pose[:, 0] += np.sin(phase * 2 + np.arange(17) * 0.3)[:, np.newaxis].squeeze() * 0.03
            pose[:, 1] += np.cos(phase * 2) * 0.02

        # 노이즈 추가
        pose += np.random.randn(*pose.shape) * 0.005
        seq[t] = pose

    return seq


def _standing_pose():
    """기본 직립 자세 (정규화 좌표 0~1)."""
    pose = np.zeros((NUM_KEYPOINTS, 2), dtype=np.float32)
    # 머리
    pose[0] = [0.50, 0.15]   # nose
    pose[14] = [0.48, 0.13]  # L eye
    pose[15] = [0.52, 0.13]  # R eye
    pose[16] = [0.46, 0.14]  # ear
    # 상체
    pose[1] = [0.50, 0.22]   # neck
    pose[2] = [0.43, 0.25]   # L shoulder
    pose[3] = [0.57, 0.25]   # R shoulder
    pose[4] = [0.40, 0.35]   # L elbow
    pose[5] = [0.60, 0.35]   # R elbow
    pose[6] = [0.38, 0.45]   # L wrist
    pose[7] = [0.62, 0.45]   # R wrist
    # 하체
    pose[8] = [0.45, 0.50]   # L hip
    pose[9] = [0.55, 0.50]   # R hip
    pose[10] = [0.44, 0.65]  # L knee
    pose[11] = [0.56, 0.65]  # R knee
    pose[12] = [0.43, 0.80]  # L ankle
    pose[13] = [0.57, 0.80]  # R ankle
    return pose


# ─── 데이터셋 ───

class SkeletonDataset(Dataset):
    def __init__(self, X, y):
        self.X = torch.from_numpy(X)  # [N, T, V, C]
        self.y = torch.from_numpy(y)  # [N]

    def __len__(self):
        return len(self.y)

    def __getitem__(self, idx):
        return self.X[idx], self.y[idx]


# ─── 학습 ───

def train(args):
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Device: {device}")

    # 데이터
    if args.synthetic or args.data_dir is None:
        print("합성 데이터 생성 중...")
        X_train, y_train = generate_synthetic_data(3000)
        X_val, y_val = generate_synthetic_data(500)
    else:
        # 실제 데이터 로드
        X_train = np.load(os.path.join(args.data_dir, "X_train.npy"))
        y_train = np.load(os.path.join(args.data_dir, "y_train.npy"))
        X_val = np.load(os.path.join(args.data_dir, "X_val.npy"))
        y_val = np.load(os.path.join(args.data_dir, "y_val.npy"))

    train_loader = DataLoader(SkeletonDataset(X_train, y_train), batch_size=64, shuffle=True)
    val_loader = DataLoader(SkeletonDataset(X_val, y_val), batch_size=64)

    print(f"학습 데이터: {X_train.shape}, 검증 데이터: {X_val.shape}")

    # 모델
    model = STGCN(NUM_CLASSES).to(device)
    total_params = sum(p.numel() for p in model.parameters())
    print(f"모델 파라미터: {total_params:,}")

    optimizer = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=args.epochs)
    criterion = nn.CrossEntropyLoss()

    best_acc = 0
    for epoch in range(args.epochs):
        # Train
        model.train()
        train_loss = 0
        train_correct = 0
        train_total = 0
        for X_batch, y_batch in train_loader:
            X_batch, y_batch = X_batch.to(device), y_batch.to(device)
            logits = model(X_batch)
            loss = criterion(logits, y_batch)

            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

            train_loss += loss.item() * X_batch.size(0)
            train_correct += (logits.argmax(1) == y_batch).sum().item()
            train_total += X_batch.size(0)

        scheduler.step()

        # Validate
        model.eval()
        val_correct = 0
        val_total = 0
        with torch.no_grad():
            for X_batch, y_batch in val_loader:
                X_batch, y_batch = X_batch.to(device), y_batch.to(device)
                logits = model(X_batch)
                val_correct += (logits.argmax(1) == y_batch).sum().item()
                val_total += X_batch.size(0)

        train_acc = train_correct / train_total
        val_acc = val_correct / val_total

        if (epoch + 1) % 5 == 0 or epoch == 0:
            print(f"Epoch {epoch+1}/{args.epochs}: "
                  f"loss={train_loss/train_total:.4f}, "
                  f"train_acc={train_acc:.3f}, val_acc={val_acc:.3f}")

        if val_acc > best_acc:
            best_acc = val_acc
            torch.save(model.state_dict(), args.output)
            print(f"  → Best model saved ({val_acc:.3f})")

    print(f"\n학습 완료. Best val acc: {best_acc:.3f}")
    print(f"모델 저장: {args.output}")

    return model


# ─── TFLite 변환 ───

def convert_to_tflite(checkpoint_path, output_path="stgcn_action.tflite"):
    """PyTorch → ONNX → TFLite 변환."""
    print(f"모델 로드: {checkpoint_path}")
    model = STGCN(NUM_CLASSES)
    model.load_state_dict(torch.load(checkpoint_path, map_location="cpu"))
    model.eval()

    # PyTorch → ONNX
    onnx_path = checkpoint_path.replace(".pth", ".onnx")
    dummy = torch.randn(1, WINDOW_SIZE, NUM_KEYPOINTS, NUM_COORDS)
    torch.onnx.export(
        model, dummy, onnx_path,
        input_names=["skeleton_input"],
        output_names=["action_output"],
        opset_version=13,
        dynamic_axes=None
    )
    print(f"ONNX 저장: {onnx_path}")

    # ONNX → TFLite
    try:
        import onnx
        from onnx_tf.backend import prepare
        import tensorflow as tf

        onnx_model = onnx.load(onnx_path)
        tf_rep = prepare(onnx_model)
        tf_rep.export_graph(onnx_path.replace(".onnx", "_tf"))

        converter = tf.lite.TFLiteConverter.from_saved_model(onnx_path.replace(".onnx", "_tf"))
        converter.optimizations = [tf.lite.Optimize.DEFAULT]

        # INT8 양자화 (대표 데이터셋)
        def representative_dataset():
            X, _ = generate_synthetic_data(100)
            for i in range(min(100, len(X))):
                yield [X[i:i+1].astype(np.float32)]

        converter.representative_dataset = representative_dataset
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        converter.inference_input_type = tf.uint8
        converter.inference_output_type = tf.uint8

        tflite_model = converter.convert()
        with open(output_path, "wb") as f:
            f.write(tflite_model)
        print(f"TFLite INT8 저장: {output_path} ({len(tflite_model)/1024:.1f} KB)")

    except ImportError:
        print("onnx-tf 또는 tensorflow 미설치. ONNX만 저장됨.")
        print("설치: pip install onnx onnx-tf tensorflow")
        print(f"수동 변환: onnx→TFLite (Colab 권장)")


# ─── 메인 ───

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ST-GCN 행동 분류 학습")
    parser.add_argument("--synthetic", action="store_true", help="합성 데이터로 학습")
    parser.add_argument("--data_dir", type=str, default=None, help="실제 데이터 디렉토리")
    parser.add_argument("--epochs", type=int, default=50, help="학습 에포크")
    parser.add_argument("--output", type=str, default="stgcn_action.pth", help="모델 저장 경로")
    parser.add_argument("--convert_only", action="store_true", help="TFLite 변환만")
    parser.add_argument("--checkpoint", type=str, default=None, help="변환할 체크포인트")
    args = parser.parse_args()

    if args.convert_only:
        if args.checkpoint is None:
            print("--checkpoint 필요")
        else:
            convert_to_tflite(args.checkpoint)
    else:
        model = train(args)
        print("\nTFLite 변환 시작...")
        convert_to_tflite(args.output)
