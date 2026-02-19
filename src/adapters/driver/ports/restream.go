package ports

// RestreamPort defines the interface for the restream manager
type RestreamPort interface {
	Start()
	Stop()
}
