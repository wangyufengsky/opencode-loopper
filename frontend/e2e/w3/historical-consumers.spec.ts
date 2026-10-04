// The same original contracts are registered per runner; never import another spec.
import { registerReadConsistencyContracts } from '../read-consistency-contracts'
registerReadConsistencyContracts()
