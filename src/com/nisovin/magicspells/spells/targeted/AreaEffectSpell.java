package com.nisovin.magicspells.spells.targeted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.nisovin.magicspells.MagicSpells;
import com.nisovin.magicspells.Subspell;
import com.nisovin.magicspells.castmodifiers.ModifierSet;
import com.nisovin.magicspells.events.SpellTargetEvent;
import com.nisovin.magicspells.events.SpellTargetLocationEvent;
import com.nisovin.magicspells.spelleffects.EffectPosition;
import com.nisovin.magicspells.spells.TargetedEntityFromLocationSpell;
import com.nisovin.magicspells.spells.TargetedEntitySpell;
import com.nisovin.magicspells.spells.TargetedLocationSpell;
import com.nisovin.magicspells.spells.TargetedSpell;
import com.nisovin.magicspells.util.BoundingBox;
import com.nisovin.magicspells.util.MagicConfig;
import com.nisovin.magicspells.util.SpellType;
import com.nisovin.magicspells.util.SpellTypes;
@SpellType(types={SpellTypes.TARGETED_LOCATION_SPELL})
public class AreaEffectSpell extends TargetedSpell implements TargetedLocationSpell {

	private int radius;
	private int verticalRadius;
	private boolean pointBlank;
	private boolean failIfNoTargets;
	private int maxTargets;
	private List<String> spellNames;
	private boolean spellSourceInCenter;
	private List<Subspell> spells;
	
	private ModifierSet locationTargetModifiers;
	private ModifierSet entityTargetModifiers;
	
	public AreaEffectSpell(MagicConfig config, String spellName) {
		super(config, spellName);
		
		radius = getConfigInt("horizontal-radius", 10);
		verticalRadius = getConfigInt("vertical-radius", 5);
		pointBlank = getConfigBoolean("point-blank", true);
		failIfNoTargets = getConfigBoolean("fail-if-no-targets", true);
		maxTargets = getConfigInt("max-targets", 0);
		spellSourceInCenter = getConfigBoolean("spell-source-in-center", false);
		spellNames = getConfigStringList("spells", null);
		
		List<String> list = getConfigStringList("location-target-modifiers", null);
		if (list != null) {
			locationTargetModifiers = new ModifierSet(list);
		}
		list = getConfigStringList("entity-target-modifiers", null);
		if (list != null) {
			entityTargetModifiers = new ModifierSet(list);
		}
	}
	
	@Override
	public void initialize() {
		super.initialize();
		
		spells = new ArrayList<Subspell>();
		
		if (spellNames != null && spellNames.size() > 0) {
			for (String spellName : spellNames) {
				Subspell spell = new Subspell(spellName);
				if (spell.process()) {
					if (spell.getSpell() instanceof TargetedLocationSpell || spell.getSpell() instanceof TargetedEntitySpell || spell.getSpell() instanceof TargetedEntityFromLocationSpell) {
						spells.add(spell);
					} else {
						MagicSpells.error("AreaEffect spell '" + name + "' attempted to use non-targeted spell '" + spellName + "'");
					}
				} else {
					MagicSpells.error("AreaEffect spell '" + name + "' attempted to use non-existant spell '" + spellName + "'");
				}
			}
			spellNames.clear();
			spellNames = null;
		}
		
		if (spells.size() == 0) {
			MagicSpells.error("AreaEffect spell '" + name + "' has no spells!");
		}
	}

	@Override
	public PostCastAction castSpell(Player player, SpellCastState state, float power, String[] args) {
		if (state == SpellCastState.NORMAL) {
			
			// get location for aoe
			Location loc = null;
			if (pointBlank) {
				loc = player.getLocation();
			} else {
				try {
					Block block = getTargetedBlock(player, power);
					if (block != null && block.getType() != Material.AIR) {
						loc = block.getLocation();
					}
				} catch (IllegalStateException e) {
					loc = null;
				}
			}
			if (loc != null) {
				SpellTargetLocationEvent event = new SpellTargetLocationEvent(this, player, loc, power);
				Bukkit.getPluginManager().callEvent(event);
				if (locationTargetModifiers != null) {
					locationTargetModifiers.apply(event);
				}
				if (event.isCancelled()) {
					loc = null;
				} else {
					loc = event.getTargetLocation();
					power = event.getPower(); //TODO make an alternative to overriding the parameter
				}
			}
			if (loc == null) {
				return noTarget(player);
			}
			
			// cast spells on nearby entities
			boolean done = doAoe(player, loc, power);
			
			// check if no targets
			if (!done && failIfNoTargets) {
				return noTarget(player);
			}
			
		}
		return PostCastAction.HANDLE_NORMALLY;
	}
	
	private boolean doAoe(Player player, Location location, float basePower) {
		int count = 0;
		
		BoundingBox box = new BoundingBox(location, radius, verticalRadius);
		List<Entity> entities = new ArrayList<Entity>(location.getWorld().getEntitiesByClasses(LivingEntity.class));
		Collections.shuffle(entities);
		for (Entity e : entities) {
			if (e instanceof LivingEntity && box.contains(e)) {
				LivingEntity target = (LivingEntity)e;
				float power = basePower;
				if (!target.isDead() && ((player == null && validTargetList.canTarget(target)) || validTargetList.canTarget(player, target))) {
					if (player != null) {
						SpellTargetEvent event = new SpellTargetEvent(this, player, target, power);
						Bukkit.getPluginManager().callEvent(event);
						if (entityTargetModifiers != null) {
							entityTargetModifiers.apply(event);
						}
						if (event.isCancelled()) {
							continue;
						} else {
							target = event.getTarget();
							power = event.getPower();
						}
					}
					for (Subspell spell : spells) {
						if (player != null) {
							if (spellSourceInCenter && spell.getSpell() instanceof TargetedEntityFromLocationSpell) {
								spell.castAtEntityFromLocation(player, location, target, power);
							} else if (spell.getSpell() instanceof TargetedEntitySpell) {
								spell.castAtEntity(player, target, power);
							} else if (spell.getSpell() instanceof TargetedLocationSpell) {
								spell.castAtLocation(player, target.getLocation(), power);
							}
						} else {
							if (spell.getSpell() instanceof TargetedEntityFromLocationSpell) {
								spell.castAtEntityFromLocation(player, location, target, power);
							} else if (spell.getSpell() instanceof TargetedEntitySpell) {
								spell.castAtEntity(player, target, power);
							} else if (spell.getSpell() instanceof TargetedLocationSpell) {
								spell.castAtLocation(player, target.getLocation(), power);
							}
						}
					}
					playSpellEffects(EffectPosition.TARGET, target);
					if (spellSourceInCenter) {
						playSpellEffectsTrail(location, target.getLocation(), true);
					} else if (player != null) {
						playSpellEffectsTrail(player.getLocation(), target.getLocation(), true);
					}
					count++;
					if (maxTargets > 0 && count >= maxTargets) {
						break;
					}
				}
			}
		}

		if (count > 0 || !failIfNoTargets) {
			if (player != null) {
				playSpellEffects(EffectPosition.CASTER, player);
			}
			playSpellEffects(EffectPosition.SPECIAL, location);
		}
		
		return count > 0;
	}

	@Override
	public boolean castAtLocation(Player caster, Location target, float power) {
		return doAoe(caster, target, power);
	}

	@Override
	public boolean castAtLocation(Location target, float power) {
		return doAoe(null, target, power);
	}
	
}
