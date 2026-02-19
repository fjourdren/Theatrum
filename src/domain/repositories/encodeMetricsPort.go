package repositories

type EncodeMetricsPort interface {
	SetEncodeQueueDepth(depth float64)
	ObserveEncodeJobDuration(seconds float64)
	IncEncodeJobsTotal(status string)
}
