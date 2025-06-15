package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	ffmpegEncoderRepository "Theatrum/adapters/driven/ffmpegEncoder/repositories"
	fileAccessRepository "Theatrum/adapters/driven/fileAccess/repositories"
	yamlConfigFileRepository "Theatrum/adapters/driven/yamlConfigFile/repositories"
	httpAdapter "Theatrum/adapters/driver/http"
	"Theatrum/adapters/driver/ports"
	rtmpAdapter "Theatrum/adapters/driver/rtmp"
	"Theatrum/domain/jobs"
	"Theatrum/domain/repositories"
	"Theatrum/domain/services"

	"go.uber.org/dig"
)

func main() {
	// Configure logging
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	// Create a context that will be canceled on shutdown
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Configure dependencies injection
	container := dig.New()

	// Provide adapters repositories
	container.Provide(func() repositories.ConfigurationPort {
		return yamlConfigFileRepository.NewYamlConfigFile()
	})
	container.Provide(func() repositories.EncoderPort {
		return ffmpegEncoderRepository.NewFfmpegEncoder()
	})
	container.Provide(func() repositories.StoragePort {
		return fileAccessRepository.NewFileAccess()
	})

	// Provide services
	container.Provide(func(configPort repositories.ConfigurationPort, storage repositories.StoragePort, templateService *services.PathTemplateService) (*services.ApplicationService, error) {
		application, server, channels, err := configPort.Load("config.yml")
		if err != nil {
			log.Printf("error loading configuration: %v", err)
			return nil, err
		}
		return services.NewApplicationService(application, server, channels, storage, templateService), nil
	})
	container.Provide(services.NewPathTemplateService)
	container.Provide(services.NewStreamService)
	container.Provide(services.NewEncodeService)

	// Provide job queue
	container.Provide(func(encodeService *services.EncodeService, storage repositories.StoragePort) *jobs.EncodeJobQueue {
		return jobs.NewEncodeJobQueue(encodeService, storage)
	})

	// Provide video detector
	container.Provide(func(
		appService *services.ApplicationService,
		encodeQueue *jobs.EncodeJobQueue,
		storage repositories.StoragePort,
		templateService *services.PathTemplateService,
	) *jobs.VideoUnencodedDetector {
		return jobs.NewVideoUnencodedDetector(appService, encodeQueue, storage, templateService)
	})

	// Provide HTTP server
	container.Provide(func(appService *services.ApplicationService, streamService *services.StreamService) ports.HttpPort {
		return httpAdapter.NewHttpServer(appService, streamService)
	})

	// Provide RTMP server
	container.Provide(func(appService *services.ApplicationService, streamService *services.StreamService) ports.RtmpPort {
		return rtmpAdapter.NewRtmpServer(appService, streamService)
	})

	// Start the application and jobs
	err := container.Invoke(func(
		appService *services.ApplicationService,
		streamService *services.StreamService,
		encodeQueue *jobs.EncodeJobQueue,
		videoDetector *jobs.VideoUnencodedDetector,
		httpServer ports.HttpPort,
		rtmpServer ports.RtmpPort,
	) {
		// Start the encode queue
		encodeQueue.Start()

		// Run video detection synchronously
		if err := videoDetector.DetectAndQueueVideos(); err != nil {
			log.Printf("Error during video detection: %v", err)
		}

		// Setup cleanup for graceful shutdown
		defer func() {
			appService.Cleanup()
			encodeQueue.Stop()
		}()

		// Start HTTP server
		serverErrors := make(chan error, 1)

		// Start the server in a goroutine
		go func() {
			serverErrors <- httpServer.StartHttpServer()
		}()

		// Start RTMP server
		rtmpErrors := make(chan error, 1)

		// Start the RTMP server in a goroutine
		go func() {
			rtmpErrors <- rtmpServer.StartRtmpServer()
		}()

		// Listen for an interrupt or terminate signal from the OS
		osSignals := make(chan os.Signal, 1)
		signal.Notify(osSignals, os.Interrupt, syscall.SIGTERM)

		// Blocking select waiting for either a signal or an error
		select {
			case err := <-serverErrors:
				log.Printf("Error starting server: %v", err)
			case err := <-rtmpErrors:
				log.Printf("Error starting RTMP server: %v", err)
			case sig := <-osSignals:
				log.Printf("Received signal: %v", sig)
			case <-ctx.Done():
				log.Printf("Context canceled")
		}

		// Create shutdown context with timeout
		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer shutdownCancel()

		// Attempt graceful shutdown
		if err := httpServer.Shutdown(shutdownCtx); err != nil {
			log.Printf("Error during server shutdown: %v", err)
		}

		if err := rtmpServer.ShutdownRtmpServer(); err != nil {
			log.Printf("Error during RTMP server shutdown: %v", err)
		}
	})

	if err != nil {
		log.Fatalf("Error during dependency injection: %v", err)
	}
}
