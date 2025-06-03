# Theatrum Streaming

![](imgs/logo.png)


A powerful and flexible streaming server that supports video on demand (VOD) with adaptive bitrate streaming capabilities. Built to handle multiple quality profiles and HLS protocol.

## Features

- 📺 **Video on Demand**
  - Support for pre-encoded video streaming
  - Automatic mp4 encoding

- 🎯 **Quality Profiles**
  - Multi-qualities management
  - Customizable audio and video bitrates

- 🔄 **Streaming Protocols**
  - HLS (HTTP Live Streaming)
  - Configurable segment duration and playlist length

- ⚙️ **Configuration**
  - Fully configurable through YAML
  - Customizable stream endpoints
  - Flexible quality profiles
  - Adjustable storage paths
  - Domain name customization
  - Global streams playlist m3u8

## Configuration

The server is configured through `config.yml`. Here's a breakdown of the main configuration sections:

### Server Configuration
```yaml
server:
  http: 8080  # HTTP port for HLS streaming
```

### Quality Profiles
Quality profiles are fully customizable. Here's an example configuration:
```yaml
quality_profiles:
  low:
    width: 640
    height: 360
    framerate: 24
    bitrate: "800k"
    codec: "libx264"
    audio:
      bitrate: "96k"
      codec: "aac"
  # Add more profiles as needed
```

### Stream Templates
The server supports different types of stream templates:

#### encoded_video
```yaml
stream_templates:
  default:  # When there is not quality, then the default directory is used in the storage path
    stream: &default_stream_config
      type: video_encoded
      path: "livestream/{username}"
      qualities:
        low: *LOW
        medium: *MEDIUM
        high: *HIGH
      distribution:
        hls:
          segment_duration: 6
          playlist_length: 5
        dash:
          segment_duration: 6
          manifest_window: 5
```

#### unencoded_video
```yaml
stream_templates:
  video:
    stream: &video_unencoded_config
      type: video_unencoded
      video_input_path: "raw_videos/{username}"
      path: "records/{username}"
      qualities:
        low: *LOW
        medium: *MEDIUM
        high: *HIGH
      distribution:
        hls:
          segment_duration: 6
          playlist_length: 5
        dash:
          segment_duration: 6
          manifest_window: 5
```


### Stream Distribution
HLS configuration includes:
- Segment duration: 6 seconds
- Playlist window: 5 segments

```yaml
distribution:
  hls:
    segment_duration: 6
    playlist_length: 5
  dash:
    segment_duration: 6
    manifest_window: 5
```

### Channel Endpoints
Channel endpoints can be configured with a templating system.

Example :
```yaml
channels:
  "/user/{username}":
    stream:
      <<: *default_stream_config
```

## Getting Started

1. Clone the repository:
```bash
git clone https://github.com/fjourdren/theatrum.git
cd theatrum
```

2. Configure your server:
   - Copy `config.yml.example` to `config.yml`
   - Adjust the configuration according to your needs:
     - Set up your quality profiles
     - Configure storage paths
     - Adjust stream templates
     - Set up endpoints

3. Run with golang:
```bash
go run ./src/cmd/main.go
```

## Requirements

- Go >= 1.24
- FFmpeg >= 4.4.0
- Storage space for video segments
- Network bandwidth according to your quality profiles

### FFmpeg Requirements
- libx264 encoder
- aac audio codec
- HLS segmenter

## Installation

### Using Docker (Recommended)

1. Build the Docker image:
```bash
docker build -t theatrum .
```

2. Run the container:
```bash
docker run -d \
  -p 8080:8080 \
  -v /path/to/your/config.yml:/app/config.yml \
  -v /path/to/your/storage:/app/storage \
  theatrum
```

## Configuration Examples

### Basic Quality Profile
```yaml
quality_profiles:
  standard:
    width: 1280
    height: 720
    framerate: 30
    bitrate: "2500k"
    codec: "libx264"
    audio:
      bitrate: "128k"
      codec: "aac"
```

### Custom Stream Template
```yaml
stream_templates:
  custom:
    stream:
      type: video_encoded
      path: "custom/{username}"
      qualities:
        standard: *standard_profile
      distribution:
        hls:
          segment_duration: 4
          playlist_length: 6
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.

## Support

For support, please open an issue in the GitHub repository or contact the maintainers.