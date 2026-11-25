"""A compact unconditional DDPM U-Net trained from scratch by New Star."""
from __future__ import annotations

import math
import torch
import torch.nn.functional as F
from torch import nn


def group_count(channels: int) -> int:
    for groups in range(min(8, channels), 0, -1):
        if channels % groups == 0:
            return groups
    return 1


def timestep_embedding(timesteps: torch.Tensor, width: int) -> torch.Tensor:
    half = width // 2
    if half == 0:
        return timesteps.float().unsqueeze(1)
    frequencies = torch.exp(
        -math.log(10000.0) * torch.arange(half, device=timesteps.device).float()
        / max(half - 1, 1)
    )
    angles = timesteps.float().unsqueeze(1) * frequencies.unsqueeze(0)
    embedding = torch.cat((torch.sin(angles), torch.cos(angles)), dim=1)
    if width % 2:
        embedding = F.pad(embedding, (0, 1))
    return embedding


class TimeResidualBlock(nn.Module):
    def __init__(self, input_channels: int, output_channels: int, time_width: int):
        super().__init__()
        self.norm1 = nn.GroupNorm(group_count(input_channels), input_channels)
        self.conv1 = nn.Conv2d(input_channels, output_channels, 3, padding=1)
        self.time_projection = nn.Linear(time_width, output_channels)
        self.norm2 = nn.GroupNorm(group_count(output_channels), output_channels)
        self.conv2 = nn.Conv2d(output_channels, output_channels, 3, padding=1)
        self.skip = (
            nn.Identity() if input_channels == output_channels
            else nn.Conv2d(input_channels, output_channels, 1)
        )

    def forward(self, values: torch.Tensor, time_embedding: torch.Tensor) -> torch.Tensor:
        hidden = self.conv1(F.silu(self.norm1(values)))
        hidden = hidden + self.time_projection(F.silu(time_embedding))[:, :, None, None]
        hidden = self.conv2(F.silu(self.norm2(hidden)))
        return hidden + self.skip(values)


class NewStarImageUNet(nn.Module):
    """Small image-space denoiser; no pretrained weights or text conditioning."""

    def __init__(self, base_channels: int = 32, time_width: int | None = None):
        super().__init__()
        if not isinstance(base_channels, int) or isinstance(base_channels, bool) or base_channels < 8:
            raise ValueError("base_channels must be an integer of at least 8")
        if time_width is None:
            time_width = base_channels * 4
        if not isinstance(time_width, int) or isinstance(time_width, bool) or time_width < 1:
            raise ValueError("time_width must be a positive integer")
        self.base_channels = base_channels
        self.time_width = time_width
        self.time_mlp = nn.Sequential(
            nn.Linear(base_channels, time_width),
            nn.SiLU(),
            nn.Linear(time_width, time_width),
        )
        self.input = nn.Conv2d(3, base_channels, 3, padding=1)
        self.down1 = TimeResidualBlock(base_channels, base_channels, time_width)
        self.downsample1 = nn.Conv2d(base_channels, base_channels * 2, 4, stride=2, padding=1)
        self.down2 = TimeResidualBlock(base_channels * 2, base_channels * 2, time_width)
        self.downsample2 = nn.Conv2d(base_channels * 2, base_channels * 4, 4, stride=2, padding=1)
        self.middle1 = TimeResidualBlock(base_channels * 4, base_channels * 4, time_width)
        self.middle2 = TimeResidualBlock(base_channels * 4, base_channels * 4, time_width)
        self.upsample2 = nn.ConvTranspose2d(base_channels * 4, base_channels * 2, 4, stride=2, padding=1)
        self.up2 = TimeResidualBlock(base_channels * 4, base_channels * 2, time_width)
        self.upsample1 = nn.ConvTranspose2d(base_channels * 2, base_channels, 4, stride=2, padding=1)
        self.up1 = TimeResidualBlock(base_channels * 2, base_channels, time_width)
        self.output_norm = nn.GroupNorm(group_count(base_channels), base_channels)
        self.output = nn.Conv2d(base_channels, 3, 3, padding=1)

    def forward(self, images: torch.Tensor, timesteps: torch.Tensor) -> torch.Tensor:
        if images.ndim != 4 or images.shape[1] != 3:
            raise ValueError("images must have shape [batch, 3, height, width]")
        if images.shape[-2] < 8 or images.shape[-1] < 8:
            raise ValueError("image height and width must each be at least 8")
        if images.shape[-2] % 4 or images.shape[-1] % 4:
            raise ValueError("image height and width must be divisible by 4")
        if timesteps.ndim != 1 or timesteps.shape[0] != images.shape[0] or timesteps.dtype != torch.long:
            raise ValueError("timesteps must be a torch.long vector with one value per image")
        if timesteps.device != images.device:
            raise ValueError("images and timesteps must be on the same device")
        if not torch.isfinite(images).all():
            raise ValueError("images must contain only finite values")
        time = self.time_mlp(timestep_embedding(timesteps, self.base_channels))
        first = self.down1(self.input(images), time)
        second = self.down2(self.downsample1(first), time)
        hidden = self.downsample2(second)
        hidden = self.middle2(self.middle1(hidden, time), time)
        hidden = self.upsample2(hidden)
        if hidden.shape[-2:] != second.shape[-2:]:
            hidden = F.interpolate(hidden, size=second.shape[-2:], mode="nearest")
        hidden = self.up2(torch.cat((hidden, second), dim=1), time)
        hidden = self.upsample1(hidden)
        if hidden.shape[-2:] != first.shape[-2:]:
            hidden = F.interpolate(hidden, size=first.shape[-2:], mode="nearest")
        hidden = self.up1(torch.cat((hidden, first), dim=1), time)
        return self.output(F.silu(self.output_norm(hidden)))


class DDPMSchedule:
    def __init__(self, steps: int = 100):
        if not isinstance(steps, int) or isinstance(steps, bool) or steps < 2:
            raise ValueError("diffusion steps must be an integer of at least 2")
        self.steps = steps
        self.betas = torch.linspace(1e-4, 0.02, steps, dtype=torch.float32)
        self.alphas = 1.0 - self.betas
        self.alpha_bars = torch.cumprod(self.alphas, dim=0)

    def to(self, device: torch.device | str) -> "DDPMSchedule":
        self.betas = self.betas.to(device)
        self.alphas = self.alphas.to(device)
        self.alpha_bars = self.alpha_bars.to(device)
        return self

    def add_noise(self, clean: torch.Tensor, timesteps: torch.Tensor,
                  noise: torch.Tensor) -> torch.Tensor:
        if clean.ndim != 4 or clean.shape[1] != 3 or noise.shape != clean.shape:
            raise ValueError("clean images and noise must share [batch, 3, height, width] shape")
        if timesteps.dtype != torch.long or timesteps.ndim != 1 or timesteps.shape[0] != clean.shape[0]:
            raise ValueError("timesteps must be a torch.long vector with one value per image")
        if clean.device != noise.device or clean.device != timesteps.device:
            raise ValueError("images, noise, and timesteps must be on the same device")
        if torch.any(timesteps < 0) or torch.any(timesteps >= self.steps):
            raise ValueError("timestep values must be within the diffusion schedule")
        if not torch.isfinite(clean).all() or not torch.isfinite(noise).all():
            raise ValueError("clean images and noise must contain only finite values")
        alpha_bar = self.alpha_bars[timesteps].view(-1, 1, 1, 1)
        return alpha_bar.sqrt() * clean + (1.0 - alpha_bar).sqrt() * noise

    @torch.inference_mode()
    def sample(self, model: NewStarImageUNet, count: int, image_size: int,
               device: torch.device | str, seed: int | None = None) -> torch.Tensor:
        if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= 16:
            raise ValueError("count must be an integer from 1 to 16")
        if not isinstance(image_size, int) or image_size < 8 or image_size % 4:
            raise ValueError("image_size must be an integer >= 8 and divisible by 4")
        device = torch.device(device)
        generator = torch.Generator(device=device)
        if seed is not None:
            if not isinstance(seed, int) or isinstance(seed, bool) or not 0 <= seed <= 2**63 - 1:
                raise ValueError("seed must be an integer between 0 and 2^63-1")
            generator.manual_seed(seed)
        values = torch.randn((count, 3, image_size, image_size), device=device, generator=generator)
        betas = self.betas.to(device)
        alphas = self.alphas.to(device)
        alpha_bars = self.alpha_bars.to(device)
        model.eval()
        for step in reversed(range(self.steps)):
            time = torch.full((count,), step, dtype=torch.long, device=device)
            predicted_noise = model(values, time)
            beta = betas[step]
            alpha = alphas[step]
            alpha_bar = alpha_bars[step]
            mean = (values - beta / torch.sqrt(1.0 - alpha_bar) * predicted_noise) / torch.sqrt(alpha)
            if step:
                previous = alpha_bars[step - 1]
                variance = beta * (1.0 - previous) / (1.0 - alpha_bar)
                noise = torch.randn(values.shape, device=device, generator=generator)
                values = mean + torch.sqrt(variance.clamp_min(0.0)) * noise
            else:
                values = mean
        return values.clamp(-1.0, 1.0)
