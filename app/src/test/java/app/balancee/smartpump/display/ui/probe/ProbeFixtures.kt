// Shared across the probe tests, and every value is one the server actually sent on 2026-09-16 —
// docs/api-probes/2026-09-16-prod-config/. Inventing plausible numbers here would repeat, in the
// test source set, the exact mistake that put a Map<FuelType, Long> in the DTO for two months.
package app.balancee.smartpump.display.ui.probe

import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.domain.model.FuelType

internal val observedConfig = PumpConfigResponse(
    pumpId = "3727aebf-3c77-4180-a818-4254cbeeae72",
    stationName = "Kachi",
    fuelType = FuelType.PETROL,
    pricePerUnit = 1490,
    updatedAt = "2026-09-15T09:44:39.187Z",
)
