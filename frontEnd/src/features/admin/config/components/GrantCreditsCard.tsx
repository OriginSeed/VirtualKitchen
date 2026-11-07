import { useState } from 'react'
import SearchableSelect from '../../../../shared/components/SearchableSelect'
import { useNotifications } from '../../../../shared/components/notifications/notificationContext'
import { errorMessage } from '../../../../shared/errors'
import { useApiData } from '../../../../shared/hooks/useApiData'
import { AiAnalyticsApi } from '../../ai-analytics/api/aiAnalyticsApi'
import { AdminConfigApi } from '../api/adminConfigApi'

const AMOUNTS = [10, 25, 50, 100, 200, 500, -10, -25, -50, -100]

/** Grant (or deduct) AI credits for one user. Every adjustment is ledgered by the backend. */
export default function GrantCreditsCard() {
  const options = useApiData('options', () => AiAnalyticsApi.options())
  const [userId, setUserId] = useState('')
  const [amount, setAmount] = useState(String(AMOUNTS[0]))
  const [busy, setBusy] = useState(false)
  const { notifySuccess, notifyError } = useNotifications()

  const users = (options.data?.users ?? []).map(user => ({ value: user.id, label: user.label }))

  const submit = async () => {
    const label = users.find(user => user.value === userId)?.label ?? userId
    setBusy(true)
    try {
      await AdminConfigApi.grantCredits(Number(userId), Number(amount))
      notifySuccess(`${Number(amount) > 0 ? 'Granted' : 'Deducted'} ${Math.abs(Number(amount))} credits for ${label}`)
    } catch (error) {
      notifyError(errorMessage(error, 'Could not adjust the credits'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="aia-card acf-group">
      <h3>Grant credits</h3>
      <div className="acf-fields">
        <div className="acf-field">
          <span className="acf-label">User</span>
          <SearchableSelect value={userId} onChange={setUserId} options={users} placeholder="Pick a user"
            emptyLabel={options.loading ? 'Loading…' : 'No users with AI activity yet'} />
        </div>
        <div className="acf-field">
          <label className="acf-label" htmlFor="grant-amount">Credits</label>
          <select id="grant-amount" value={amount} onChange={(event) => setAmount(event.target.value)}>
            {AMOUNTS.map(value => <option key={value} value={value}>{value > 0 ? `+${value}` : value}</option>)}
          </select>
        </div>
        <div className="acf-field acf-field--action">
          <button type="button" className="aia-button aia-button--primary" disabled={!userId || busy} onClick={submit}>
            {busy ? 'Applying…' : 'Apply'}
          </button>
        </div>
      </div>
    </section>
  )
}
