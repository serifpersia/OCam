#include <obs-module.h>
#include <util/platform.h>
#include <util/threading.h>
#include <util/dstr.h>

/* --- Platform Specific Includes & Definitions --- */
#ifdef _WIN32
    #include <winsock2.h>
    #include <ws2tcpip.h>
    #pragma comment(lib, "ws2_32.lib")

    #define CLOSESOCKET closesocket
    #define SHUTDOWN_FLAGS SD_BOTH
    #define MSG_NOSIGNAL 0
    
    // Windows setsockopt takes const char*
    #define SOCKOPT_VAL_TYPE const char* 
    
    // MSVC doesn't define ssize_t by default
    #include <BaseTsd.h>
    typedef SSIZE_T ssize_t;
#else
    #include <sys/socket.h>
    #include <netinet/in.h>
    #include <netinet/tcp.h>
    #include <arpa/inet.h>
    #include <unistd.h>
    #include <netdb.h>

    #define CLOSESOCKET close
    #define SHUTDOWN_FLAGS SHUT_RDWR
    
    // Linux setsockopt takes void*
    #define SOCKOPT_VAL_TYPE void*
#endif

#include <pthread.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <stdlib.h>

#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/log.h>
#include <libavutil/opt.h>

#define VIDEO_PORT 27183
#define CONTROL_PORT 27184
#define AUDIO_PORT 27185
#define NAME_BUFFER_SIZE 64

/* --- Endianness Helpers (Portable) --- */
// Network to Host (32-bit) - ntohl is standard on Win/Lin
static inline uint32_t portable_ntohl(uint32_t val) {
    return ntohl(val);
}

// Network to Host (64-bit) - Custom implementation to avoid non-standard headers
static inline uint64_t portable_ntohll(uint64_t val) {
    uint32_t high = (uint32_t)(val >> 32);
    uint32_t low  = (uint32_t)(val & 0xFFFFFFFF);
    return ((uint64_t)ntohl(low) << 32) | ntohl(high);
}

static float befloattoh(uint32_t val) {
    uint32_t host_val = portable_ntohl(val);
    float f;
    memcpy(&f, &host_val, sizeof(float));
    return f;
}

struct ocam_res {
    int w;
    int h;
};

struct ocam_source {
    obs_source_t *source;

    // Threads
    pthread_t network_thread;
    pthread_t control_thread;
    pthread_t audio_thread;
    
    volatile bool thread_running;
    bool network_thread_active;
    bool control_thread_active;
    bool audio_thread_active;

    // Sockets
    int video_server_fd;
    int video_client_fd;
    int control_server_fd;
    int control_client_fd;
    int audio_server_fd;
    int audio_client_fd;

    pthread_mutex_t mutex;

    // Control/Config State
    struct ocam_res *supported_resolutions;
    int supported_res_count;
    int iso_min, iso_max;
    int exp_min, exp_max;
    float focus_min;
    bool flash_available;
    bool caps_received;

    int current_w, current_h;
    int current_fps;
    int current_bitrate;
    bool current_flash;
    int current_iso;
    int current_exp;
    int current_focus;

    // Video State
    uint32_t width;
    uint32_t height;
    AVCodecContext *codec_ctx;
    AVFrame *decoded_frame;
    uint8_t *extradata;
    int extradata_size;
    bool codec_initialized;
    int64_t timestamp_offset;
    bool first_frame_received;

    // Audio State
    AVCodecContext *audio_codec_ctx;
    AVFrame *audio_decoded_frame;
    uint8_t *audio_extradata;
    int audio_extradata_size;
    bool audio_codec_initialized;
    int64_t audio_timestamp_offset;
    bool first_audio_received;
};

// Helper: Wait for data or timeout, returning false if thread stops
static bool wait_for_socket(int fd, struct ocam_source *s, bool read) {
    while (s->thread_running) {
        fd_set set;
        FD_ZERO(&set);
        FD_SET(fd, &set);

        struct timeval tv = {0, 100000};
        int res = select(fd + 1, read ? &set : NULL, read ? NULL : &set, NULL, &tv);

        if (res > 0) return true;
        if (res < 0) {
             if (errno == EINTR) continue;
             return false;
        }
    }
    return false;
}

static ssize_t read_bytes_fully(int fd, void *buf, size_t len, struct ocam_source *s) {
    size_t total_read = 0;
    while (total_read < len && s->thread_running) {
        if (!wait_for_socket(fd, s, true)) return -1;
        
        ssize_t bytes_read = recv(fd, (char*)buf + total_read, (int)(len - total_read), 0);
        if (bytes_read <= 0) return bytes_read;
        total_read += bytes_read;
    }
    return total_read;
}

static int accept_with_timeout(int server_fd, struct ocam_source *s) {
    if (!wait_for_socket(server_fd, s, true)) return -1;
    return (int)accept(server_fd, NULL, NULL);
}

static void send_control_command(struct ocam_source *s, uint8_t cmd_id, uint32_t arg1, uint32_t arg2) {
    pthread_mutex_lock(&s->mutex);
    if (s->control_client_fd != -1) {
        uint8_t buffer[9];
        buffer[0] = cmd_id;
        buffer[1] = (arg1 >> 24) & 0xFF; buffer[2] = (arg1 >> 16) & 0xFF; buffer[3] = (arg1 >> 8) & 0xFF; buffer[4] = (arg1) & 0xFF;
        buffer[5] = (arg2 >> 24) & 0xFF; buffer[6] = (arg2 >> 16) & 0xFF; buffer[7] = (arg2 >> 8) & 0xFF; buffer[8] = (arg2) & 0xFF;
        
        if (send(s->control_client_fd, (const char*)buffer, 9, MSG_NOSIGNAL) < 0) {
            blog(LOG_WARNING, "[OCAM] Send Error: Connection lost");
        }
    }
    pthread_mutex_unlock(&s->mutex);
}

static int create_bind_socket(int port) {
    int fd;
    int opt = 1;
    struct sockaddr_in address;

    if ((fd = (int)socket(AF_INET, SOCK_STREAM, 0)) == 0) return -1;

    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, (SOCKOPT_VAL_TYPE)&opt, sizeof(opt));
    #ifdef SO_REUSEPORT
    setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, (SOCKOPT_VAL_TYPE)&opt, sizeof(opt));
    #endif

    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, (SOCKOPT_VAL_TYPE)&opt, sizeof(opt));

    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(port);

    int retries = 5;
    while (retries > 0) {
        if (bind(fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
            blog(LOG_WARNING, "[OCAM] Bind retry port %d...", port);
            os_sleep_ms(1000);
            retries--;
        } else {
            return fd;
        }
    }
    return -1;
}

static void sync_settings_to_phone(struct ocam_source *s) {
    if (s->current_w > 0 && s->current_h > 0) {
        send_control_command(s, 0x01, s->current_w, s->current_h);
        os_sleep_ms(50);
    }
    if (s->current_fps > 0) send_control_command(s, 0x02, s->current_fps, 0);
    if (s->current_bitrate > 0) send_control_command(s, 0x03, s->current_bitrate * 1000000, 0);

    send_control_command(s, 0x09, s->current_flash ? 1 : 0, 0);
    if (s->current_iso >= 0) send_control_command(s, 0x06, s->current_iso, 0);
    if (s->current_exp >= 0) send_control_command(s, 0x07, s->current_exp, 0);
    if (s->current_focus >= -1) send_control_command(s, 0x08, s->current_focus, 0);
}

static void *control_thread_func(void *data) {
    struct ocam_source *s = data;

    s->control_server_fd = create_bind_socket(CONTROL_PORT);
    if (s->control_server_fd < 0) return NULL;
    if (listen(s->control_server_fd, 1) < 0) { CLOSESOCKET(s->control_server_fd); return NULL; }

    uint8_t trash_buffer[1024];

    while (s->thread_running) {
        int client = accept_with_timeout(s->control_server_fd, s);

        if (client < 0) continue;
        if (!s->thread_running) { CLOSESOCKET(client); break; }
        
        // TCP_NODELAY
        int opt = 1;
        setsockopt(client, IPPROTO_TCP, TCP_NODELAY, (SOCKOPT_VAL_TYPE)&opt, sizeof(opt));

        pthread_mutex_lock(&s->mutex);
        s->control_client_fd = client;
        pthread_mutex_unlock(&s->mutex);

        blog(LOG_INFO, "[OCAM-CTRL] Connected. Syncing settings...");
        sync_settings_to_phone(s);
        send_control_command(s, 0x05, 0, 0);

        while (s->thread_running) {
            uint8_t header[5];
            if (read_bytes_fully(client, header, 5, s) <= 0) break;

            uint8_t pkt_type = header[0];
            uint32_t payload_len = portable_ntohl(*(uint32_t*)(header + 1));

            if (pkt_type == 0x10) {
                uint8_t *payload = malloc(payload_len);
                if (read_bytes_fully(client, payload, payload_len, s) == payload_len) {

                    pthread_mutex_lock(&s->mutex);
                    int offset = 0;
                    uint8_t res_count = payload[offset++];

                    if (s->supported_resolutions) free(s->supported_resolutions);
                    s->supported_resolutions = malloc(sizeof(struct ocam_res) * res_count);
                    s->supported_res_count = res_count;

                    for(int i=0; i<res_count; i++) {
                        uint32_t w = portable_ntohl(*(uint32_t*)(payload + offset)); offset += 4;
                        uint32_t h = portable_ntohl(*(uint32_t*)(payload + offset)); offset += 4;
                        s->supported_resolutions[i].w = w;
                        s->supported_resolutions[i].h = h;
                    }

                    s->iso_min = (int32_t)portable_ntohl(*(uint32_t*)(payload + offset)); offset += 4;
                    s->iso_max = (int32_t)portable_ntohl(*(uint32_t*)(payload + offset)); offset += 4;
                    s->exp_min = (int32_t)portable_ntohl(*(uint32_t*)(payload + offset)); offset += 4;
                    s->exp_max = (int32_t)portable_ntohl(*(uint32_t*)(payload + offset)); offset += 4;
                    s->focus_min = befloattoh(*(uint32_t*)(payload + offset)); offset += 4;
                    s->flash_available = payload[offset++];

                    s->caps_received = true;
                    pthread_mutex_unlock(&s->mutex);

                    blog(LOG_INFO, "[OCAM] Capabilities updated.");
                }
                free(payload);
            } else {
                size_t remaining = payload_len;
                while(remaining > 0) {
                    size_t to_read = (remaining > sizeof(trash_buffer)) ? sizeof(trash_buffer) : remaining;
                    if (read_bytes_fully(client, trash_buffer, to_read, s) <= 0) break;
                    remaining -= to_read;
                }
            }
        }

        pthread_mutex_lock(&s->mutex);
        if (s->control_client_fd != -1) { CLOSESOCKET(s->control_client_fd); s->control_client_fd = -1; }
        s->caps_received = false;
        pthread_mutex_unlock(&s->mutex);
    }
    CLOSESOCKET(s->control_server_fd);
    return NULL;
}

static obs_properties_t *ocam_get_properties(void *data) {
    struct ocam_source *s = data;
    obs_properties_t *props = obs_properties_create();

    obs_property_t *list = obs_properties_add_list(props, "resolution", "Resolution", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    pthread_mutex_lock(&s->mutex);
    if (s->caps_received && s->supported_res_count > 0) {
        for (int i = 0; i < s->supported_res_count; i++) {
            struct dstr label = {0};
            dstr_printf(&label, "%dx%d", s->supported_resolutions[i].w, s->supported_resolutions[i].h);
            obs_property_list_add_string(list, label.array, label.array);
            dstr_free(&label);
        }
    } else {
        obs_property_list_add_string(list, "1280x720", "1280x720");
        obs_property_list_add_string(list, "1920x1080", "1920x1080");
        if (!s->caps_received) obs_property_set_description(list, "Resolution (Connect phone to populate)");
    }

    obs_property_t *fps_list = obs_properties_add_list(props, "fps", "FPS", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(fps_list, "60 FPS", 60);
    obs_property_list_add_int(fps_list, "30 FPS", 30);
    obs_property_list_add_int(fps_list, "24 FPS", 24);
    obs_property_list_add_int(fps_list, "15 FPS", 15);

    obs_property_t *bit_list = obs_properties_add_list(props, "bitrate", "Bitrate", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(bit_list, "1 Mbps", 1);
    obs_property_list_add_int(bit_list, "2 Mbps", 2);
    obs_property_list_add_int(bit_list, "4 Mbps", 4);
    obs_property_list_add_int(bit_list, "6 Mbps", 6);
    obs_property_list_add_int(bit_list, "8 Mbps", 8);
    obs_property_list_add_int(bit_list, "12 Mbps", 12);
    obs_property_list_add_int(bit_list, "20 Mbps", 20);
    obs_property_list_add_int(bit_list, "50 Mbps (High)", 50);

    obs_properties_add_bool(props, "flash", "Flash / Torch");

    obs_properties_t *manual_grp = obs_properties_create();
    int iso_max = (s->caps_received && s->iso_max > 0) ? s->iso_max : 3200;
    obs_properties_add_int_slider(manual_grp, "iso", "ISO (0=Auto)", 0, iso_max, 1);
    int exp_max = (s->caps_received && s->exp_max > 0) ? s->exp_max : 100000;
    obs_properties_add_int_slider(manual_grp, "exposure", "Exposure µs (0=Auto)", 0, exp_max, 100);
    obs_properties_add_int_slider(manual_grp, "focus", "Focus (-1=Auto, 0-1000 Manual)", -1, 1000, 1);

    pthread_mutex_unlock(&s->mutex);
    obs_properties_add_group(props, "manual_controls", "Manual Controls", OBS_GROUP_NORMAL, manual_grp);
    return props;
}

static void ocam_get_defaults(obs_data_t *settings) {
    obs_data_set_default_string(settings, "resolution", "1280x720");
    obs_data_set_default_int(settings, "fps", 30);
    obs_data_set_default_int(settings, "bitrate", 2);
    obs_data_set_default_bool(settings, "flash", false);
    obs_data_set_default_int(settings, "iso", 0);
    obs_data_set_default_int(settings, "exposure", 0);
    obs_data_set_default_int(settings, "focus", -1);
}

static void ocam_update(void *data, obs_data_t *settings) {
    struct ocam_source *s = data;

    const char *res_str = obs_data_get_string(settings, "resolution");
    int w = 0, h = 0;
    if (sscanf(res_str, "%dx%d", &w, &h) == 2) {
        if (w != s->current_w || h != s->current_h) {
            blog(LOG_INFO, "[OCAM] Setting Resolution: %dx%d", w, h);
            send_control_command(s, 0x01, w, h);
            s->current_w = w; s->current_h = h;
        }
    }

    int fps = (int)obs_data_get_int(settings, "fps");
    if (fps != s->current_fps) {
        blog(LOG_INFO, "[OCAM] Setting FPS: %d", fps);
        send_control_command(s, 0x02, fps, 0);
        s->current_fps = fps;
    }

    int bitrate_mbps = (int)obs_data_get_int(settings, "bitrate");
    if (bitrate_mbps != s->current_bitrate) {
        blog(LOG_INFO, "[OCAM] Setting Bitrate: %d Mbps", bitrate_mbps);
        send_control_command(s, 0x03, bitrate_mbps * 1000000, 0);
        s->current_bitrate = bitrate_mbps;
    }

    bool flash = obs_data_get_bool(settings, "flash");
    if (flash != s->current_flash) {
        send_control_command(s, 0x09, flash ? 1 : 0, 0);
        s->current_flash = flash;
    }

    int iso = (int)obs_data_get_int(settings, "iso");
    if (iso != s->current_iso) {
        send_control_command(s, 0x06, iso, 0);
        s->current_iso = iso;
    }

    int exp = (int)obs_data_get_int(settings, "exposure");
    if (exp != s->current_exp) {
        send_control_command(s, 0x07, exp, 0);
        s->current_exp = exp;
    }

    int focus = (int)obs_data_get_int(settings, "focus");
    if (focus != s->current_focus) {
        send_control_command(s, 0x08, focus, 0);
        s->current_focus = focus;
    }
}

// --- Video FFmpeg Utils ---

static inline enum video_format convert_pixel_format(int f) {
    switch (f) {
        case AV_PIX_FMT_YUV420P: return VIDEO_FORMAT_I420;
        case AV_PIX_FMT_YUVJ420P: return VIDEO_FORMAT_I420;
        case AV_PIX_FMT_NV12: return VIDEO_FORMAT_NV12;
        default: return VIDEO_FORMAT_NONE;
    }
}

static inline enum video_colorspace convert_color_space(enum AVColorSpace s) {
    switch (s) {
        case AVCOL_SPC_BT709: return VIDEO_CS_709;
        case AVCOL_SPC_SMPTE170M: return VIDEO_CS_601;
        default: return VIDEO_CS_DEFAULT;
    }
}

static void cleanup_ffmpeg(struct ocam_source *s) {
    if (s->codec_ctx) { avcodec_free_context(&s->codec_ctx); s->codec_ctx = NULL; }
    if (s->decoded_frame) { av_frame_free(&s->decoded_frame); s->decoded_frame = NULL; }
    if (s->extradata) { free(s->extradata); s->extradata = NULL; }
    s->extradata_size = 0;
    s->codec_initialized = false;
}

static bool init_ffmpeg(struct ocam_source *s) {
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (!codec) return false;

    s->codec_ctx = avcodec_alloc_context3(codec);
    if (s->extradata_size > 0) {
        s->codec_ctx->extradata = (uint8_t*)av_malloc(s->extradata_size + AV_INPUT_BUFFER_PADDING_SIZE);
        memcpy(s->codec_ctx->extradata, s->extradata, s->extradata_size);
        s->codec_ctx->extradata_size = s->extradata_size;
    }

    s->codec_ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    av_opt_set(s->codec_ctx->priv_data, "tune", "zerolatency", 0);

    s->decoded_frame = av_frame_alloc();
    if (avcodec_open2(s->codec_ctx, codec, NULL) < 0) return false;

    s->codec_initialized = true;
    return true;
}

static void *network_thread_func(void *data) {
    struct ocam_source *s = data;
    AVPacket *packet = NULL;

    s->video_server_fd = create_bind_socket(VIDEO_PORT);
    if (s->video_server_fd < 0) return NULL;
    if (listen(s->video_server_fd, 1) < 0) { CLOSESOCKET(s->video_server_fd); return NULL; }

    while (s->thread_running) {
        int client = accept_with_timeout(s->video_server_fd, s);

        if (client < 0) continue;
        if (!s->thread_running) { CLOSESOCKET(client); break; }
        
        int opt = 1;
        setsockopt(client, IPPROTO_TCP, TCP_NODELAY, (SOCKOPT_VAL_TYPE)&opt, sizeof(opt));

        pthread_mutex_lock(&s->mutex);
        s->video_client_fd = client;
        pthread_mutex_unlock(&s->mutex);

        char name[NAME_BUFFER_SIZE];
        uint32_t config[3];
        if (read_bytes_fully(client, name, NAME_BUFFER_SIZE, s) <= 0 || read_bytes_fully(client, config, sizeof(config), s) <= 0) {
            CLOSESOCKET(client); continue;
        }

        blog(LOG_INFO, "[OCAM] Video Connection Established. Waiting for stream...");

        cleanup_ffmpeg(s);
        s->first_frame_received = false;
        packet = av_packet_alloc();

        while (s->thread_running) {
            uint64_t pts_net;
            uint32_t size_net;

            if (read_bytes_fully(client, &pts_net, sizeof(pts_net), s) <= 0) break;
            if (read_bytes_fully(client, &size_net, sizeof(size_net), s) <= 0) break;

            uint64_t pts = portable_ntohll(pts_net);
            uint32_t size = portable_ntohl(size_net);

            if (av_new_packet(packet, size) < 0) break;
            if (read_bytes_fully(client, packet->data, size, s) != size) { av_packet_unref(packet); break; }

            // PTS 0 = Config Packet (Stream Restart)
            if (pts == 0) {
                blog(LOG_INFO, "[OCAM] Config Packet (Stream Restart).");
                uint8_t *new_ptr = realloc(s->extradata, s->extradata_size + size);
                if (new_ptr) {
                    s->extradata = new_ptr;
                    memcpy(s->extradata + s->extradata_size, packet->data, size);
                    s->extradata_size += size;
                }
                if (s->codec_initialized) avcodec_flush_buffers(s->codec_ctx);
                s->first_frame_received = false;
            }

            if (!s->codec_initialized) {
                if (!init_ffmpeg(s)) { av_packet_unref(packet); break; }
            }

            int64_t pts_ns = (int64_t)pts * 1000;

            if (!s->first_frame_received && pts > 0) {
                s->timestamp_offset = (int64_t)os_gettime_ns() - pts_ns;
                s->first_frame_received = true;
            }

            packet->pts = pts;
            if (avcodec_send_packet(s->codec_ctx, packet) >= 0) {
                while (avcodec_receive_frame(s->codec_ctx, s->decoded_frame) >= 0) {
                    if ((uint32_t)s->decoded_frame->width != s->width || (uint32_t)s->decoded_frame->height != s->height) {
                        s->width = (uint32_t)s->decoded_frame->width;
                        s->height = (uint32_t)s->decoded_frame->height;
                    }

                    enum video_format obs_fmt = convert_pixel_format(s->decoded_frame->format);
                    if (obs_fmt == VIDEO_FORMAT_NONE) continue;

                    struct obs_source_frame obs_frame = {0};
                    for (int i = 0; i < MAX_AV_PLANES; i++) {
                        obs_frame.data[i] = s->decoded_frame->data[i];
                        obs_frame.linesize[i] = abs(s->decoded_frame->linesize[i]);
                    }
                    obs_frame.format = obs_fmt;
                    obs_frame.width = s->decoded_frame->width;
                    obs_frame.height = s->decoded_frame->height;
                    obs_frame.full_range = (s->decoded_frame->color_range == AVCOL_RANGE_JPEG);
                    obs_frame.timestamp = pts_ns + s->timestamp_offset;

                    enum video_colorspace cs = convert_color_space(s->decoded_frame->colorspace);
                    video_format_get_parameters_for_format(cs, s->decoded_frame->color_range == AVCOL_RANGE_JPEG ? VIDEO_RANGE_FULL : VIDEO_RANGE_PARTIAL,
                                                           obs_fmt, obs_frame.color_matrix, obs_frame.color_range_min, obs_frame.color_range_max);

                    obs_source_output_video(s->source, &obs_frame);
                }
            }
            av_packet_unref(packet);
        }

        pthread_mutex_lock(&s->mutex);
        if(s->video_client_fd != -1) { CLOSESOCKET(s->video_client_fd); s->video_client_fd = -1; }
        pthread_mutex_unlock(&s->mutex);
        if (packet) { av_packet_free(&packet); packet = NULL; }
        cleanup_ffmpeg(s);
    }
    if (packet) av_packet_free(&packet);
    CLOSESOCKET(s->video_server_fd);
    return NULL;
}

// --- Audio FFmpeg Utils ---

static void cleanup_audio_ffmpeg(struct ocam_source *s) {
    if (s->audio_codec_ctx) { avcodec_free_context(&s->audio_codec_ctx); s->audio_codec_ctx = NULL; }
    if (s->audio_decoded_frame) { av_frame_free(&s->audio_decoded_frame); s->audio_decoded_frame = NULL; }
    if (s->audio_extradata) { free(s->audio_extradata); s->audio_extradata = NULL; }
    s->audio_extradata_size = 0;
    s->audio_codec_initialized = false;
}

static bool init_audio_ffmpeg(struct ocam_source *s) {
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_AAC);
    if (!codec) return false;

    s->audio_codec_ctx = avcodec_alloc_context3(codec);
    
    if (s->audio_extradata_size > 0) {
        s->audio_codec_ctx->extradata = (uint8_t*)av_malloc(s->audio_extradata_size + AV_INPUT_BUFFER_PADDING_SIZE);
        memcpy(s->audio_codec_ctx->extradata, s->audio_extradata, s->audio_extradata_size);
        s->audio_codec_ctx->extradata_size = s->audio_extradata_size;
    }

    s->audio_decoded_frame = av_frame_alloc();
    if (avcodec_open2(s->audio_codec_ctx, codec, NULL) < 0) return false;

    s->audio_codec_initialized = true;
    return true;
}

static void *audio_thread_func(void *data) {
    struct ocam_source *s = data;
    AVPacket *packet = NULL;

    s->audio_server_fd = create_bind_socket(AUDIO_PORT);
    if (s->audio_server_fd < 0) return NULL;
    if (listen(s->audio_server_fd, 1) < 0) { CLOSESOCKET(s->audio_server_fd); return NULL; }

    while (s->thread_running) {
        int client = accept_with_timeout(s->audio_server_fd, s);

        if (client < 0) continue;
        if (!s->thread_running) { CLOSESOCKET(client); break; }
        
        // TCP_NODELAY
        int opt = 1;
        setsockopt(client, IPPROTO_TCP, TCP_NODELAY, (SOCKOPT_VAL_TYPE)&opt, sizeof(opt));

        pthread_mutex_lock(&s->mutex);
        s->audio_client_fd = client;
        pthread_mutex_unlock(&s->mutex);

        // Handshake: Read 4 bytes magic "AAC "
        uint32_t magic;
        if (read_bytes_fully(client, &magic, sizeof(magic), s) <= 0) {
            CLOSESOCKET(client); continue;
        }

        blog(LOG_INFO, "[OCAM] Audio Connection Established.");

        cleanup_audio_ffmpeg(s);
        s->first_audio_received = false;
        packet = av_packet_alloc();

        while (s->thread_running) {
            uint64_t pts_net;
            uint32_t size_net;

            if (read_bytes_fully(client, &pts_net, sizeof(pts_net), s) <= 0) break;
            if (read_bytes_fully(client, &size_net, sizeof(size_net), s) <= 0) break;

            uint64_t pts = portable_ntohll(pts_net);
            uint32_t size = portable_ntohl(size_net);

            if (av_new_packet(packet, size) < 0) break;
            if (read_bytes_fully(client, packet->data, size, s) != size) { av_packet_unref(packet); break; }

            // Init codec if needed (using first packet as config/data)
            if (!s->audio_codec_initialized) {
                 if (s->audio_extradata_size == 0) {
                     s->audio_extradata = malloc(size);
                     memcpy(s->audio_extradata, packet->data, size);
                     s->audio_extradata_size = size;
                 }
                 if (!init_audio_ffmpeg(s)) { av_packet_unref(packet); continue; }
            }

            int64_t pts_ns = (int64_t)pts * 1000;
            if (!s->first_audio_received) {
                // Sync audio with video offset logic
                s->audio_timestamp_offset = (int64_t)os_gettime_ns() - pts_ns;
                s->first_audio_received = true;
            }

            packet->pts = pts;
            
            if (avcodec_send_packet(s->audio_codec_ctx, packet) >= 0) {
                while (avcodec_receive_frame(s->audio_codec_ctx, s->audio_decoded_frame) >= 0) {
                    
                    struct obs_source_audio obs_audio = {0};
                    
                    // OBS expects planar float for FLOAT_PLANAR, interleaved for others
                    // FFmpeg's AAC decoder usually outputs FLTP (Float Planar)
                    for(int i=0; i<MAX_AV_PLANES; i++) {
                         obs_audio.data[i] = s->audio_decoded_frame->data[i];
                    }
                    
                    obs_audio.frames = s->audio_decoded_frame->nb_samples;
                    
                    // Map Format
                    if (s->audio_codec_ctx->sample_fmt == AV_SAMPLE_FMT_FLTP) obs_audio.format = AUDIO_FORMAT_FLOAT_PLANAR;
                    else if (s->audio_codec_ctx->sample_fmt == AV_SAMPLE_FMT_FLT) obs_audio.format = AUDIO_FORMAT_FLOAT;
                    else if (s->audio_codec_ctx->sample_fmt == AV_SAMPLE_FMT_S16P) obs_audio.format = AUDIO_FORMAT_16BIT_PLANAR;
                    else obs_audio.format = AUDIO_FORMAT_16BIT;

                    // Channel Layout (Modern FFmpeg uses ch_layout, older uses channels)
                    #if LIBAVCODEC_VERSION_INT >= AV_VERSION_INT(59, 37, 100)
                        int channels = s->audio_codec_ctx->ch_layout.nb_channels;
                    #else
                        int channels = s->audio_codec_ctx->channels;
                    #endif

                    obs_audio.speakers = (channels == 2) ? SPEAKERS_STEREO : SPEAKERS_MONO;
                    obs_audio.samples_per_sec = s->audio_codec_ctx->sample_rate;
                    obs_audio.timestamp = pts_ns + s->audio_timestamp_offset;

                    obs_source_output_audio(s->source, &obs_audio);
                }
            }
            av_packet_unref(packet);
        }

        pthread_mutex_lock(&s->mutex);
        if(s->audio_client_fd != -1) { CLOSESOCKET(s->audio_client_fd); s->audio_client_fd = -1; }
        pthread_mutex_unlock(&s->mutex);
        if (packet) { av_packet_free(&packet); packet = NULL; }
        cleanup_audio_ffmpeg(s);
    }
    if (packet) av_packet_free(&packet);
    CLOSESOCKET(s->audio_server_fd);
    return NULL;
}


// --- Main Lifecycle ---

static void ocam_destroy(void *data) {
    struct ocam_source *s = data;
    s->thread_running = false;

    pthread_mutex_lock(&s->mutex);
    if (s->video_client_fd != -1) { shutdown(s->video_client_fd, SHUTDOWN_FLAGS); CLOSESOCKET(s->video_client_fd); s->video_client_fd = -1; }
    if (s->control_client_fd != -1) { shutdown(s->control_client_fd, SHUTDOWN_FLAGS); CLOSESOCKET(s->control_client_fd); s->control_client_fd = -1; }
    if (s->audio_client_fd != -1) { shutdown(s->audio_client_fd, SHUTDOWN_FLAGS); CLOSESOCKET(s->audio_client_fd); s->audio_client_fd = -1; }
    
    if (s->video_server_fd != -1) { shutdown(s->video_server_fd, SHUTDOWN_FLAGS); CLOSESOCKET(s->video_server_fd); s->video_server_fd = -1; }
    if (s->control_server_fd != -1) { shutdown(s->control_server_fd, SHUTDOWN_FLAGS); CLOSESOCKET(s->control_server_fd); s->control_server_fd = -1; }
    if (s->audio_server_fd != -1) { shutdown(s->audio_server_fd, SHUTDOWN_FLAGS); CLOSESOCKET(s->audio_server_fd); s->audio_server_fd = -1; }
    pthread_mutex_unlock(&s->mutex);

    if (s->network_thread_active) pthread_join(s->network_thread, NULL);
    if (s->control_thread_active) pthread_join(s->control_thread, NULL);
    if (s->audio_thread_active) pthread_join(s->audio_thread, NULL);

    if (s->supported_resolutions) free(s->supported_resolutions);
    pthread_mutex_destroy(&s->mutex);
    cleanup_ffmpeg(s);
    cleanup_audio_ffmpeg(s);
    bfree(s);
}

static void *ocam_create(obs_data_t *settings, obs_source_t *source) {
    UNUSED_PARAMETER(settings);
    struct ocam_source *s = bzalloc(sizeof(struct ocam_source));
    s->source = source;
    s->thread_running = true;
    s->video_server_fd = -1; s->video_client_fd = -1;
    s->control_server_fd = -1; s->control_client_fd = -1;
    s->audio_server_fd = -1; s->audio_client_fd = -1;

    // Init Cache
    s->current_w = -1; s->current_h = -1;
    s->current_fps = -1; s->current_bitrate = -1;
    s->current_iso = -1; s->current_exp = -1; s->current_focus = -100;

    pthread_mutex_init(&s->mutex, NULL);

    if (pthread_create(&s->network_thread, NULL, network_thread_func, s) == 0) s->network_thread_active = true;
    if (pthread_create(&s->control_thread, NULL, control_thread_func, s) == 0) s->control_thread_active = true;
    if (pthread_create(&s->audio_thread, NULL, audio_thread_func, s) == 0) s->audio_thread_active = true;

    ocam_update(s, settings);
    return s;
}

static const char *ocam_get_name(void *unused) { UNUSED_PARAMETER(unused); return "OCam Source"; }
static uint32_t ocam_get_width(void *data) { struct ocam_source *s = data; return s->width ? s->width : 1280; }
static uint32_t ocam_get_height(void *data) { struct ocam_source *s = data; return s->height ? s->height : 720; }

static struct obs_source_info ocam_source_info = {
    .id = "ocam_source",
    .type = OBS_SOURCE_TYPE_INPUT,
    .output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO,
    .get_name = ocam_get_name,
    .create = ocam_create,
    .destroy = ocam_destroy,
    .get_width = ocam_get_width,
    .get_height = ocam_get_height,
    .update = ocam_update,
    .get_properties = ocam_get_properties,
    .get_defaults = ocam_get_defaults,
    .icon_type = OBS_ICON_TYPE_CAMERA,
};

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("obs-ocam-source", "en-US")
bool obs_module_load(void) { obs_register_source(&ocam_source_info); return true; }
void obs_module_unload(void) {}
