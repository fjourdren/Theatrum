package models

import "Theatrum/domain/models"

// ConnectionInfo holds connection details from RTMP connections
type ConnectionInfo struct {
	App     string
	TCURL   string
	Stream  *models.Stream
	Vars    map[string]string // Extracted variables from TCURL pattern matching
}

// GetVar returns a specific variable from the stored URL variables
func (c *ConnectionInfo) GetVar(key string) (string, bool) {
	if c.Vars != nil {
		val, exists := c.Vars[key]
		return val, exists
	}
	return "", false
}

// GetVars returns all stored URL variables (copy to prevent modification)
func (c *ConnectionInfo) GetVars() map[string]string {
	if c.Vars == nil {
		return make(map[string]string)
	}
	
	vars := make(map[string]string)
	for k, v := range c.Vars {
		vars[k] = v
	}
	return vars
}

// GetUsername returns the username variable if it exists
func (c *ConnectionInfo) GetUsername() (string, bool) {
	return c.GetVar("username")
}

// GetHost returns the host variable if it exists  
func (c *ConnectionInfo) GetHost() (string, bool) {
	return c.GetVar("host")
}

// GetAppName returns the app variable if it exists
func (c *ConnectionInfo) GetAppName() (string, bool) {
	return c.GetVar("app")
} 