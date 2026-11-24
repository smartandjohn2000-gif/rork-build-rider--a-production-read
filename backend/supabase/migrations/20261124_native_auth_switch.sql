-- RideAX auth model switch: Rork Auth -> native Supabase Auth.
-- All identity columns stay text and hold the Supabase auth user's UUID as a
-- string, so the apps' payload shapes are unchanged. Policies move from the
-- Rork-only `user_id()` helper to Supabase's `auth.uid()`.

-- ---------- policies: profiles ----------
DROP POLICY IF EXISTS "Users can insert own profile" ON profiles;
DROP POLICY IF EXISTS "Users can update own profile" ON profiles;
CREATE POLICY "Users can insert own profile"
  ON profiles FOR INSERT
  WITH CHECK (auth.uid()::text = id);
CREATE POLICY "Users can update own profile"
  ON profiles FOR UPDATE
  USING (auth.uid()::text = id)
  WITH CHECK (auth.uid()::text = id);

-- ---------- policies: rides ----------
DROP POLICY IF EXISTS "Riders and drivers read their own rides" ON rides;
DROP POLICY IF EXISTS "Riders create their own rides" ON rides;
DROP POLICY IF EXISTS "Riders and drivers update their own rides" ON rides;
CREATE POLICY "Riders and drivers read their own rides"
  ON rides FOR SELECT
  USING ((auth.uid()::text = rider_id) OR (auth.uid()::text = driver_id));
CREATE POLICY "Riders create their own rides"
  ON rides FOR INSERT
  WITH CHECK (auth.uid()::text = rider_id);
CREATE POLICY "Riders and drivers update their own rides"
  ON rides FOR UPDATE
  USING ((auth.uid()::text = rider_id) OR (auth.uid()::text = driver_id))
  WITH CHECK ((auth.uid()::text = rider_id) OR (auth.uid()::text = driver_id));

-- ---------- policies: driver_profiles ----------
DROP POLICY IF EXISTS "Drivers can insert own driver profile" ON driver_profiles;
DROP POLICY IF EXISTS "Drivers can update own driver profile" ON driver_profiles;
CREATE POLICY "Drivers can insert own driver profile"
  ON driver_profiles FOR INSERT
  WITH CHECK (auth.uid()::text = id);
CREATE POLICY "Drivers can update own driver profile"
  ON driver_profiles FOR UPDATE
  USING (auth.uid()::text = id)
  WITH CHECK (auth.uid()::text = id);

-- ---------- policies: payments (read-only for users; edge functions use the service role) ----------
DROP POLICY IF EXISTS "Riders read own payments" ON payments;
CREATE POLICY "Riders read own payments"
  ON payments FOR SELECT
  USING (auth.uid()::text = rider_id);

-- ---------- auto-create a profile row on sign-up ----------
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public
AS $$
BEGIN
  INSERT INTO public.profiles (id, email, full_name, role)
  VALUES (
    new.id::text,
    new.email,
    coalesce(new.raw_user_meta_data->>'full_name', new.raw_user_meta_data->>'name', split_part(new.email, '@', 1)),
    coalesce(new.raw_user_meta_data->>'role', 'rider')
  )
  ON CONFLICT (id) DO NOTHING;
  RETURN new;
END;
$$;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();
